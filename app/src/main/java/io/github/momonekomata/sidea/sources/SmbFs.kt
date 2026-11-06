package io.github.momonekomata.sidea.sources

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.security.bc.BCSecurityProvider
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Windows shares and most NAS boxes (Samba), SMB 2 and 3. Paths are "/share/folder/file"; the
 * share comes first because SMB can't list shares without extra RPC. One session per server,
 * shared by browsing and the parallel download's connections, rebuilt if it drops.
 */
class SmbFs(private val server: NasServer) : RemoteFs {
    override val root: String = "/" + server.basePath.trim('/')
    override val ranges = true

    // BouncyCastle for NTLM's MD4, which Android's own crypto doesn't have.
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build(),
    )
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    @Synchronized
    private fun share(name: String): DiskShare {
        shares[name]?.takeIf { it.isConnected }?.let { return it }
        val s = session?.takeIf { it.connection.isConnected } ?: run {
            shares.clear()
            val conn = client.connect(server.address, if (server.port > 0) server.port else 445)
            val user = server.username
            val (domain, account) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
            val auth = if (account.isEmpty()) AuthenticationContext.guest() else AuthenticationContext(account, server.password.toCharArray(), domain)
            conn.authenticate(auth).also { session = it }
        }
        val share = s.connectShare(name) as? DiskShare ?: throw IOException("$name isn't a folder share")
        shares[name] = share
        return share
    }

    /** "/Music/Albums/x.flac" → share "Music", path "Albums\x.flac". */
    private fun split(path: String): Pair<String, String> {
        val p = path.trim('/')
        if (p.isEmpty()) throw IOException("Set the share name as the server's folder (e.g. Music), as SMB can't list shares")
        return p.substringBefore('/') to p.substringAfter('/', "").replace('/', '\\')
    }

    private inline fun <T> retrying(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        // A dropped connection: forget it and try once more on a new one.
        synchronized(this) { session = null; shares.clear() }
        block()
    }

    private inline fun <T> dropOnFailure(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        synchronized(this) { session = null; shares.clear() }
        throw e
    }

    override fun list(folder: String): List<RemoteItem> = retrying {
        val (shareName, dir) = split(folder)
        share(shareName).list(dir).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith("._") }.map { f ->
            val isDir = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            RemoteItem(f.fileName, folder.trimEnd('/') + "/" + f.fileName, isDir, f.endOfFile)
        }
    }

    // Not retried here: data may already have gone to the sink. The download resumes the piece.
    override fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean) = dropOnFailure {
        val (shareName, path) = split(id)
        share(shareName).openFile(
            path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f ->
            // Positioned reads: each piece of a parallel download starts where it should.
            val buf = ByteArray(256 * 1024)
            var pos = from
            while (to < 0 || pos < to) {
                val want = if (to < 0) buf.size else minOf(buf.size.toLong(), to - pos).toInt()
                val n = f.read(buf, pos, 0, want)
                if (n <= 0) break
                pos += n
                if (!sink(buf, n)) break
            }
        }
    }

    override fun write(folder: String, name: String, bytes: ByteArray) = retrying {
        val (shareName, dir) = split(folder.trimEnd('/') + "/" + name)
        share(shareName).openFile(
            dir, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { f -> f.outputStream.use { it.write(bytes) } }
    }
}
