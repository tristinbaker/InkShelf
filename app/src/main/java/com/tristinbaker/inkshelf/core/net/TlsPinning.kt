package com.tristinbaker.inkshelf.core.net

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier

/**
 * Adds a single pinned certificate for [serverUrl]'s host.
 *
 * With no pin recorded we deliberately leave strict system trust in place, so a
 * mistyped host fails loudly rather than downgrading to "trust anything".
 */
fun OkHttpClient.Builder.applyPinning(
    serverUrl: ServerUrl,
    pinner: TlsPinner,
): OkHttpClient.Builder {
    val host = serverUrl.host
    val fingerprint = pinner.pinnedFor(host) ?: return this

    certificatePinner(
        CertificatePinner.Builder()
            .add(host, fingerprint)
            .build(),
    )
    // The pin, not the CN/SAN, is the identity check now: a self-hosted cert
    // frequently has a hostname that does not match how the user reaches it.
    hostnameVerifier(PinnedHostnameVerifier)
    return this
}

private object PinnedHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: javax.net.ssl.SSLSession): Boolean = true
}

/** Reports whether a response used TLS, for the diagnostics screen. */
fun Response.presentedLeaf(): X509Certificate? {
    val chain = handshake?.peerCertificates.orEmpty()
    return chain.firstOrNull() as? X509Certificate
}

val Request.effectiveUrl: String get() = url.toString()
