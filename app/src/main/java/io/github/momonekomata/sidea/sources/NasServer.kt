package io.github.momonekomata.sidea.sources

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Where music can live besides the phone. [remote] kinds go through [RemoteBrowser]; WebDAV and
 * FTPS have browsers of their own. [cloud] kinds sign in through the browser instead of a password.
 */
enum class NasKind(val label: String, val scheme: String, val remote: Boolean, val cloud: Boolean = false) {
    WEBDAV("WebDAV", "https", false),
    FTPS("FTPS", "ftps", false),
    SMB("SMB", "smb", true),
    SFTP("SFTP", "sftp", true),
    FTP("FTP", "ftp", true),
    GDRIVE("Google Drive", "gdrive", true, cloud = true),
    ONEDRIVE("OneDrive", "onedrive", true, cloud = true),
    DROPBOX("Dropbox", "dropbox", true, cloud = true),
}

data class NasServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: NasKind,
    /** WebDAV: full base URL, e.g. https://nas.local:5006/music. Others: host name or IP. Cloud: unused. */
    val address: String,
    /** 0 means the protocol's usual port. */
    val port: Int = 0,
    /** FTPS only: implicit TLS (port 990) instead of AUTH TLS on port 21. */
    val implicitTls: Boolean = false,
    /** Folder to start in. SMB: the share first, e.g. "Music" or "Media/Music". */
    val basePath: String = "/",
    val username: String = "",
    val password: String = "",
    /** Accept a self-signed certificate, but only the one whose SHA-256 is pinned below. */
    val allowSelfSigned: Boolean = false,
    /** TLS certificate (WebDAV, FTPS) or SSH host key (SFTP) trusted on first use. */
    val pinnedSha256: String? = null,
    /** Cloud: the app registration Side A signs in as (see the README), and its secret if any. */
    val clientId: String = "",
    val clientSecret: String = "",
    /** Cloud: the long-lived token from signing in. */
    val refreshToken: String = "",
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("address", address)
        .put("port", port).put("implicitTls", implicitTls).put("basePath", basePath)
        .put("username", username).put("password", password)
        .put("allowSelfSigned", allowSelfSigned).put("pinnedSha256", pinnedSha256 ?: JSONObject.NULL)
        .put("clientId", clientId).put("clientSecret", clientSecret).put("refreshToken", refreshToken)

    companion object {
        fun fromJson(o: JSONObject) = NasServer(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = NasKind.valueOf(o.getString("kind")),
            address = o.getString("address"),
            port = o.optInt("port"),
            implicitTls = o.optBoolean("implicitTls"),
            basePath = o.optString("basePath", "/"),
            username = o.optString("username"),
            password = o.optString("password"),
            allowSelfSigned = o.optBoolean("allowSelfSigned"),
            pinnedSha256 = if (o.isNull("pinnedSha256")) null else o.optString("pinnedSha256"),
            clientId = o.optString("clientId"),
            clientSecret = o.optString("clientSecret"),
            refreshToken = o.optString("refreshToken"),
        )
    }
}

/** NAS settings, passwords included, live in Keystore-backed encrypted preferences. */
class NasStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "nas_servers",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun all(): List<NasServer> {
        val arr = JSONArray(prefs.getString("servers", "[]"))
        return (0 until arr.length()).map { NasServer.fromJson(arr.getJSONObject(it)) }
    }

    fun save(server: NasServer) {
        val list = all().filterNot { it.id == server.id } + server
        prefs.edit().putString("servers", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun delete(id: String) {
        val list = all().filterNot { it.id == id }
        prefs.edit().putString("servers", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
