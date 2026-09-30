package com.tristinbaker.inkshelf.core.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface TlsProbe {
    /** The device already trusts this certificate. Nothing to pin. */
    data object TrustedSystem : TlsProbe

    /** Untrusted, but pinnable. [fingerprint] is the leaf's SHA-256. */
    data class SelfSigned(val fingerprint: String, val subject: String) : TlsProbe

    data class Failed(val reason: String) : TlsProbe
}

/**
 * TLS for self-hosted servers.
 *
 * Audiobookshelf behind a reverse proxy very often presents a certificate the
 * device does not trust. Rather than accepting anything, we do trust-on-first-use
 * pinning: the first connection to an untrusted host is probed, its leaf
 * certificate's SHA-256 is recorded, and from then on only that exact
 * certificate is accepted. If the cert later changes we fail loudly instead of
 * silently trusting a new key.
 */
class TlsPinner(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("inkshelf_tls", Context.MODE_PRIVATE)

    private val _lastProbe = MutableStateFlow<TlsProbe?>(null)
    val lastProbe: StateFlow<TlsProbe?> = _lastProbe.asStateFlow()

    fun pin(hostPort: String, fingerprint: String) {
        prefs.edit().putString(hostPort, fingerprint).apply()
        Log.i(TAG, "pinned certificate for $hostPort")
    }

    fun pinnedFor(hostPort: String): String? = prefs.getString(hostPort, null)

    fun unpin(hostPort: String) {
        prefs.edit().remove(hostPort).apply()
    }

    /**
     * Inspects the certificate a host presents *without* trusting it, so the
     * login screen can show the user what they are about to pin.
     */
    suspend fun probe(serverUrl: ServerUrl): TlsProbe = withContext(Dispatchers.IO) {
        try {
            val factory = SSLSocketFactory.getDefault()
            val socket = factory.createSocket(serverUrl.host, serverUrl.port) as SSLSocket
            socket.use {
                it.startHandshake()
                val chain = it.session.peerCertificates
                val leaf = chain.firstOrNull() as? X509Certificate
                    ?: return@use TlsProbe.Failed("server presented no X.509 certificate")
                if (isSystemTrusted(leaf)) {
                    TlsProbe.TrustedSystem
                } else {
                    TlsProbe.SelfSigned(sha256(leaf), leaf.subjectX500Principal.name)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TLS probe failed for ${serverUrl.hostPort}", t)
            TlsProbe.Failed("${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        }
    }

    private fun isSystemTrusted(cert: X509Certificate): Boolean = try {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trust = tmf.trustManagers.firstOrNull() as? X509TrustManager
        trust?.checkServerTrusted(arrayOf(cert), "RSA")
        true
    } catch (_: CertificateException) {
        false
    }

    companion object {
        private const val TAG = "TlsPinner"

        fun sha256(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}
