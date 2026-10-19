package io.github.momonekomata.sidea.sources

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The server presented a certificate the system doesn't trust and nothing is pinned yet. */
class UnpinnedCertificateException(val sha256: String, val subject: String) :
    CertificateException("Untrusted certificate $subject (SHA-256 $sha256)")

fun X509Certificate.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }

/**
 * Normal system trust, plus trust-on-first-use for NAS boxes with self-signed certificates:
 * the first connection fails with [UnpinnedCertificateException] so the UI can show the
 * fingerprint, and once the user accepts it only that exact certificate is let through.
 */
class PinningTrustManager(
    private val allowSelfSigned: Boolean,
    private val pinnedSha256: String?,
) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (pinnedSha256 != null && chain.firstOrNull()?.sha256() == pinnedSha256) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw e
            if (!allowSelfSigned) throw e
            throw UnpinnedCertificateException(leaf.sha256(), leaf.subjectX500Principal.name)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    /** True when the peer is the pinned certificate, which stands in for a hostname match. */
    fun isPinned(cert: X509Certificate?) = pinnedSha256 != null && cert?.sha256() == pinnedSha256

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinningTrustManager), null) }.socketFactory
}

/** Walks the cause chain, since TLS libraries wrap the trust manager's exception. */
fun Throwable.findUnpinned(): UnpinnedCertificateException? {
    var t: Throwable? = this
    while (t != null) {
        if (t is UnpinnedCertificateException) return t
        t = t.cause
    }
    return null
}
