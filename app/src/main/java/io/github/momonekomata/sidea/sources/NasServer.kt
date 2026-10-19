package io.github.momonekomata.sidea.sources

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NasKind { WEBDAV, FTPS }

data class NasServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: NasKind,
    /** WebDAV: full base URL, e.g. https://nas.local:5006/music. FTPS: host name or IP. */
    val address: String,
    /** FTPS only; 0 means the default (990 implicit, 21 explicit). */
    val port: Int = 0,
    /** FTPS only: implicit TLS (port 990) instead of AUTH TLS on port 21. */
    val implicitTls: Boolean = false,
    /** FTPS only: folder to start in. */
    val basePath: String = "/",
    val username: String = "",
    val password: String = "",
    /** Accept a self-signed certificate, but only the one whose SHA-256 is pinned below. */
    val allowSelfSigned: Boolean = false,
    val pinnedSha256: String? = null,
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("kind", kind.name).put("address", address)
        .put("port", port).put("implicitTls", implicitTls).put("basePath", basePath)
        .put("username", username).put("password", password)
        .put("allowSelfSigned", allowSelfSigned).put("pinnedSha256", pinnedSha256 ?: JSONObject.NULL)

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
