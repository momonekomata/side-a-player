package io.github.momonekomata.sidea.sources

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException

/**
 * Plain FTP, for a NAS on the home network that has nothing better switched on. Nothing is
 * encrypted, the password included, so FTPS or SFTP are the better choice when the NAS has them.
 * FTP can carry one transfer per connection, so every read and write gets its own.
 */
class FtpFs(private val server: NasServer) : RemoteFs {
    override val root: String = "/" + server.basePath.trim('/')
    override val ranges = true

    private fun connect(): FTPClient {
        val ftp = FTPClient().apply {
            controlEncoding = "UTF-8"
            setAutodetectUTF8(true)
            connectTimeout = 10_000
            setDataTimeout(java.time.Duration.ofSeconds(30))
        }
        ftp.connect(server.address, if (server.port > 0) server.port else 21)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            ftp.quietly()
            throw IOException("FTP server refused the connection: ${ftp.replyString.trim()}")
        }
        if (!ftp.login(server.username.ifEmpty { "anonymous" }, server.password)) {
            ftp.quietly()
            throw IOException("Wrong user name or password")
        }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        return ftp
    }

    private fun FTPClient.quietly() {
        runCatching { if (isConnected) { logout(); disconnect() } }
    }

    private inline fun <T> ftp(block: (FTPClient) -> T): T {
        val ftp = connect()
        try {
            return block(ftp)
        } finally {
            ftp.quietly()
        }
    }

    override fun list(folder: String): List<RemoteItem> = ftp { ftp ->
        val files = ftp.listFiles(folder) ?: emptyArray()
        if (!FTPReply.isPositiveCompletion(ftp.replyCode) && files.isEmpty()) throw IOException("Listing $folder failed: ${ftp.replyString.trim()}")
        files.filter { it.name != "." && it.name != ".." && !it.name.startsWith("._") }
            .map { RemoteItem(it.name, folder.trimEnd('/') + "/" + it.name, it.isDirectory, it.size) }
    }

    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) = ftp { ftp ->
        ftp.restartOffset = from
        val input = ftp.retrieveFileStream(id) ?: throw IOException("Can't open $id: ${ftp.replyString.trim()}")
        // Stopping early (a range, or playback moved on) aborts the transfer; no reply to wait for then.
        input.use { it.pump(if (to < 0) -1 else to - from, sink) }
        if (to < 0) ftp.completePendingCommand()
    }

    override fun write(folder: String, name: String, bytes: ByteArray) = ftp { ftp ->
        val target = folder.trimEnd('/') + "/" + name
        if (!ftp.storeFile(target, bytes.inputStream())) throw IOException("Can't save $target: ${ftp.replyString.trim()}")
    }
}
