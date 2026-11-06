package io.github.momonekomata.sidea.sources

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.security.MessageDigest

/**
 * SFTP over SSH, with a user name and password. The server's host key is trusted the first time
 * and pinned; a different key later is refused, as it would be from a server pretending to be the
 * NAS. One SSH session per server; each list, read and write gets its own channel, so the
 * parallel download's pieces don't wait for each other.
 */
class SftpFs(private var server: NasServer, private val save: (NasServer) -> Unit) : RemoteFs {
    override val root: String = "/" + server.basePath.trim('/')
    override val ranges = true

    private var session: Session? = null

    @Synchronized
    private fun session(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        val jsch = JSch()
        jsch.hostKeyRepository = PinnedHostKey()
        val s = jsch.getSession(server.username, server.address, if (server.port > 0) server.port else 22)
        s.setPassword(server.password)
        s.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = server.password
            override fun promptPassword(message: String?) = true
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) {}
        }
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.timeout = 30_000
        s.connect(15_000)
        session = s
        return s
    }

    private inline fun <T> sftp(block: (ChannelSftp) -> T): T {
        val ch = try {
            session().openChannel("sftp") as ChannelSftp
        } catch (e: Exception) {
            // The session dropped: one fresh try.
            synchronized(this) { session = null }
            session().openChannel("sftp") as ChannelSftp
        }
        try {
            ch.connect(15_000)
            return block(ch)
        } finally {
            ch.disconnect()
        }
    }

    override fun list(folder: String): List<RemoteItem> = sftp { ch ->
        ch.ls(folder).map { it as ChannelSftp.LsEntry }
            .filter { it.filename != "." && it.filename != ".." && !it.filename.startsWith("._") }
            .map { e -> RemoteItem(e.filename, folder.trimEnd('/') + "/" + e.filename, e.attrs.isDir, e.attrs.size) }
    }

    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) = sftp { ch ->
        ch.get(id, null, from).use { it.pump(if (to < 0) -1 else to - from, sink) }
    }

    override fun write(folder: String, name: String, bytes: ByteArray) = sftp { ch ->
        ch.put(bytes.inputStream(), folder.trimEnd('/') + "/" + name, ChannelSftp.OVERWRITE)
    }

    /** Trust on first use: the first key seen is pinned in the server's settings. */
    private inner class PinnedHostKey : HostKeyRepository {
        private fun fingerprint(key: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }

        override fun check(host: String?, key: ByteArray): Int {
            val pinned = server.pinnedSha256
            val seen = fingerprint(key)
            return when {
                pinned == null -> {
                    server = server.copy(pinnedSha256 = seen)
                    save(server)
                    HostKeyRepository.OK
                }
                pinned == seen -> HostKeyRepository.OK
                else -> throw IOException("${server.name}'s SSH host key changed. If you reinstalled the NAS, delete and add the server again.")
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "side-a"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
