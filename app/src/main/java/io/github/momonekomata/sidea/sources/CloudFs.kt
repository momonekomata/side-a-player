package io.github.momonekomata.sidea.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Where each cloud signs in, and what it's asked for. */
private data class OAuthProvider(val authUrl: String, val tokenUrl: String, val scope: String?, val extra: Map<String, String>, val redirect: String)

private const val PORT = 53682

private fun provider(kind: NasKind) = when (kind) {
    NasKind.GDRIVE -> OAuthProvider(
        "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        // Whole Drive: music folders are the user's own, and .lrc files go next to the songs.
        "https://www.googleapis.com/auth/drive", mapOf("access_type" to "offline", "prompt" to "consent"),
        "http://127.0.0.1:$PORT/",
    )
    NasKind.ONEDRIVE -> OAuthProvider(
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize", "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        "offline_access Files.ReadWrite", emptyMap(),
        "http://localhost:$PORT/",
    )
    NasKind.DROPBOX -> OAuthProvider(
        "https://www.dropbox.com/oauth2/authorize", "https://api.dropboxapi.com/oauth2/token",
        null, mapOf("token_access_type" to "offline"),
        "http://127.0.0.1:$PORT/",
    )
    else -> error("$kind isn't a cloud")
}

/** The redirect address to register with each cloud for Side A (see the README). */
fun NasKind.redirectUri(): String = provider(this).redirect

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private fun Response.ensure(what: String): Response {
    if (!isSuccessful) {
        val body = runCatching { body?.string()?.take(300) }.getOrNull().orEmpty()
        close()
        throw IOException("$what: HTTP $code $body".trim())
    }
    return this
}

/**
 * Signing in to a cloud the way desktop apps do: the phone's browser opens the cloud's own sign-in
 * page, and the cloud sends the browser back to a tiny web server Side A runs on this phone
 * (127.0.0.1, port 53682) with a one-time code. PKCE ties the code to this sign-in. Side A never
 * sees the password, only a token, kept in the encrypted server settings.
 */
object CloudSignIn {
    private val random = SecureRandom()

    private fun randomString(bytes: Int): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Returns the refresh token. Waits up to five minutes for the user to sign in. */
    suspend fun signIn(context: Context, server: NasServer): String = withContext(Dispatchers.IO) {
        if (server.clientId.isBlank()) throw IOException("Add the app's client ID first (README: Cloud storage)")
        val p = provider(server.kind)
        val verifier = randomString(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = randomString(16)
        val url = p.authUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", server.clientId)
            .addQueryParameter("redirect_uri", p.redirect)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .apply {
                p.scope?.let { addQueryParameter("scope", it) }
                p.extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
        val code = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1")).use { ss ->
            ss.soTimeout = 300_000
            withContext(Dispatchers.Main) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            waitForCode(ss, state)
        }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", p.redirect)
            .add("client_id", server.clientId)
            .add("code_verifier", verifier)
            .apply { if (server.clientSecret.isNotBlank()) add("client_secret", server.clientSecret) }
            .build()
        http.newCall(Request.Builder().url(p.tokenUrl).post(form).build()).execute().ensure("Signing in to ${server.kind.label}").use { resp ->
            val o = JSONObject(resp.body!!.string())
            o.optString("refresh_token").ifBlank { throw IOException("${server.kind.label} gave no long-lived token; remove Side A from your account's connected apps and sign in again") }
        }
    }

    /** Serves the browser's return visit: the code in, a "done" page out. Other requests (favicon) get a 404. */
    private fun waitForCode(ss: ServerSocket, state: String): String {
        while (true) {
            ss.accept().use { sock ->
                val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val out = sock.getOutputStream()
                fun reply(status: String, html: String) {
                    val body = html.toByteArray()
                    out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
                val page = { msg: String -> "<html><body style=\"font-family:sans-serif;padding:2em;background:#111;color:#eee\"><h2>Side A</h2><p>$msg</p></body></html>" }
                when {
                    query["error"] != null -> {
                        reply("200 OK", page("Signing in didn't finish (${query["error"]}). You can close this tab."))
                        throw IOException("Sign-in cancelled: ${query["error_description"] ?: query["error"]}")
                    }
                    query["code"] != null -> {
                        if (query["state"] != state) {
                            reply("400 Bad Request", page("This sign-in doesn't match the one Side A started."))
                            throw IOException("Sign-in answer didn't match; try again")
                        }
                        reply("200 OK", page("Signed in. Go back to Side A."))
                        return query["code"]!!
                    }
                    else -> reply("404 Not Found", page("Nothing here."))
                }
            }
        }
    }
}

/**
 * Access tokens for one cloud server: refreshed from the stored refresh token as they run out, and
 * once more when the cloud says one is no longer valid. A new refresh token (OneDrive hands one out
 * with each refresh) is saved straight away.
 */
class CloudAuth(private var server: NasServer, private val save: (NasServer) -> Unit) {
    val kind get() = server.kind
    private var access: String? = null
    private var expiresAt = 0L

    @Synchronized
    private fun token(forceNew: Boolean = false): String {
        if (!forceNew) access?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }?.let { return it }
        if (server.refreshToken.isBlank()) throw IOException("Sign in to ${server.kind.label} first (edit the server)")
        val p = provider(server.kind)
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", server.refreshToken)
            .add("client_id", server.clientId)
            .apply {
                if (server.clientSecret.isNotBlank()) add("client_secret", server.clientSecret)
                p.scope?.let { if (server.kind == NasKind.ONEDRIVE) add("scope", it) }
            }
            .build()
        http.newCall(Request.Builder().url(p.tokenUrl).post(form).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(
                    if ("invalid_grant" in text) "${server.kind.label} sign-in has expired; edit the server and sign in again"
                    else "Refreshing ${server.kind.label} sign-in: HTTP ${resp.code}",
                )
            }
            val o = JSONObject(text)
            access = o.getString("access_token")
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
            o.optString("refresh_token").takeIf { it.isNotBlank() && it != server.refreshToken }?.let {
                server = server.copy(refreshToken = it)
                save(server)
            }
        }
        return access!!
    }

    /** Runs [build]'s request with a token; on 401 gets a fresh token and tries once more. Caller closes. */
    fun call(what: String, build: Request.Builder.() -> Unit): Response {
        fun go(token: String) = http.newCall(Request.Builder().apply(build).header("Authorization", "Bearer $token").build()).execute()
        var resp = go(token())
        if (resp.code == 401) {
            resp.close()
            resp = go(token(forceNew = true))
        }
        return resp.ensure(what)
    }

    fun json(what: String, build: Request.Builder.() -> Unit): JSONObject = call(what, build).use { JSONObject(it.body!!.string()) }
}

private fun Request.Builder.range(from: Long, to: Long) = apply {
    if (from > 0 || to >= 0) header("Range", "bytes=$from-" + if (to >= 0) "${to - 1}" else "")
}

private fun Response.pumpTo(from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) = use { resp ->
    val input = resp.body!!.byteStream()
    // A server that ignored the range sent the whole file: skip to the start.
    if (resp.code == 200 && from > 0) {
        var left = from
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) throw IOException("The cloud doesn't support reading from the middle")
            left -= s
        }
    }
    input.pump(if (to < 0) -1 else to - from, sink)
}

/** Google Drive, through the Drive API v3. Folders and files are item ids; "root" is My Drive. */
class GoogleDriveFs(private val auth: CloudAuth) : RemoteFs {
    override val root = "root"
    override val ranges = true
    private val api = "https://www.googleapis.com/drive/v3/files"
    private val folderType = "application/vnd.google-apps.folder"

    private fun query(q: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page: String? = null
        do {
            val url = api.toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,shortcutDetails)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val o = auth.json("Listing Google Drive") { url(url) }
            val files = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += files.getJSONObject(i)
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        return out
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    override fun list(folder: String): List<RemoteItem> = query("${quote(folder)} in parents and trashed = false").mapNotNull { f ->
        // Shortcuts stand for what they point at.
        val sc = f.optJSONObject("shortcutDetails")
        val id = sc?.optString("targetId") ?: f.getString("id")
        val type = sc?.optString("targetMimeType") ?: f.optString("mimeType")
        if (type.startsWith("application/vnd.google-apps.") && type != folderType) return@mapNotNull null // Docs, Sheets…
        RemoteItem(f.getString("name"), id, type == folderType, f.optString("size").toLongOrNull() ?: -1)
    }

    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) =
        auth.call("Reading from Google Drive") { url("$api/$id?alt=media&supportsAllDrives=true"); range(from, to) }.pumpTo(from, to, sink)

    override fun write(folder: String, name: String, bytes: ByteArray) {
        val existing = query("name = ${quote(name)} and ${quote(folder)} in parents and trashed = false").firstOrNull()
        val media = bytes.toRequestBody("application/octet-stream".toMediaType())
        if (existing != null) {
            auth.call("Saving to Google Drive") {
                url("https://www.googleapis.com/upload/drive/v3/files/${existing.getString("id")}?uploadType=media&supportsAllDrives=true")
                patch(media)
            }.close()
        } else {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folder)).toString()
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(media)
                .build()
            auth.call("Saving to Google Drive") {
                url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true")
                post(body)
            }.close()
        }
    }
}

/** OneDrive (personal, work or school) through Microsoft Graph. Items are ids; "root" is the top. */
class OneDriveFs(private val auth: CloudAuth) : RemoteFs {
    override val root = "root"
    override val ranges = true
    private val api = "https://graph.microsoft.com/v1.0/me/drive/items"

    override fun list(folder: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        var next: String? = "$api/$folder/children?\$top=999&\$select=id,name,size,folder,file"
        while (next != null) {
            val o = auth.json("Listing OneDrive") { url(next!!) }
            val items = o.optJSONArray("value") ?: JSONArray()
            for (i in 0 until items.length()) {
                val f = items.getJSONObject(i)
                out += RemoteItem(f.getString("name"), f.getString("id"), f.has("folder"), f.optLong("size", -1))
            }
            next = o.optString("@odata.nextLink").takeIf { it.isNotBlank() }
        }
        return out
    }

    // /content redirects to a short-lived download address that takes ranges without the token.
    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) =
        auth.call("Reading from OneDrive") { url("$api/$id/content"); range(from, to) }.pumpTo(from, to, sink)

    override fun write(folder: String, name: String, bytes: ByteArray) {
        val enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        auth.call("Saving to OneDrive") {
            url("$api/$folder:/$enc:/content")
            put(bytes.toRequestBody("application/octet-stream".toMediaType()))
        }.close()
    }
}

/** Dropbox through API v2. Items are paths; "" is the top of the Dropbox (or the app folder). */
class DropboxFs(private val auth: CloudAuth) : RemoteFs {
    override val root = ""
    override val ranges = true

    /** Dropbox-API-Arg is a header, so the JSON in it must be plain ASCII. */
    private fun arg(o: JSONObject) = buildString {
        for (c in o.toString()) if (c.code < 0x80) append(c) else append("\\u%04x".format(c.code))
    }

    private fun rpc(endpoint: String, body: JSONObject): JSONObject = auth.json("Dropbox") {
        url("https://api.dropboxapi.com/2/files/$endpoint")
        post(body.toString().toRequestBody("application/json".toMediaType()))
    }

    override fun list(folder: String): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        var o = rpc("list_folder", JSONObject().put("path", folder).put("limit", 2000))
        while (true) {
            val entries = o.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val tag = e.optString(".tag")
                if (tag != "file" && tag != "folder") continue
                out += RemoteItem(e.getString("name"), e.getString("path_display"), tag == "folder", e.optLong("size", -1))
            }
            if (!o.optBoolean("has_more")) break
            o = rpc("list_folder/continue", JSONObject().put("cursor", o.getString("cursor")))
        }
        return out
    }

    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) =
        auth.call("Reading from Dropbox") {
            url("https://content.dropboxapi.com/2/files/download")
            header("Dropbox-API-Arg", arg(JSONObject().put("path", id)))
            post(ByteArray(0).toRequestBody(null))
            range(from, to)
        }.pumpTo(from, to, sink)

    override fun write(folder: String, name: String, bytes: ByteArray) {
        auth.call("Saving to Dropbox") {
            url("https://content.dropboxapi.com/2/files/upload")
            header("Dropbox-API-Arg", arg(JSONObject().put("path", folder.trimEnd('/') + "/" + name).put("mode", "overwrite").put("mute", true)))
            post(bytes.toRequestBody("application/octet-stream".toMediaType()))
        }.close()
    }
}
