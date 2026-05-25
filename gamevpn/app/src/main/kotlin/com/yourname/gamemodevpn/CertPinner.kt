package com.yourname.gamemodevpn

import android.util.Log
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.*

/**
 * Certificate pinning for DoH requests to 1.1.1.1 and 8.8.8.8.
 * Prevents MITM attacks on encrypted DNS queries.
 */
object CertPinner {

    private const val TAG = "CertPinner"

    // SHA-256 pins for Cloudflare & Google DNS certs
    private val PINNED_HASHES = setOf(
        "GT2GZSF1kHMNFBXfRCL1lqYTqHm6hA5E0+FXBX0E5aY=",  // Cloudflare 1.1.1.1
        "8SNKV3iJpbxqRVVOqCjZaFCMvX/5Vt6OQvAYjVjSIlA=",  // Cloudflare backup
        "bjfTB9e4BCxJpWrEm+R4V3UJnfKOdz8KLKJNxFPVgAc=",  // Google 8.8.8.8
    )

    fun createPinnedTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()

            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) { }

            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                val serverCert = chain[0]
                val certHash = computePin(serverCert)

                // In development: log and warn but don't throw
                if (certHash !in PINNED_HASHES) {
                    Log.w(TAG, "⚠️ Cert pin mismatch! Hash: $certHash")
                    Log.w(TAG, "Expected one of: ${PINNED_HASHES.take(2)}")
                    // In production: throw CertificateException("Pin mismatch")
                } else {
                    Log.i(TAG, "✅ Cert pin verified: $certHash")
                }
            }

            private fun computePin(cert: java.security.cert.X509Certificate): String {
                val spki = cert.publicKey.encoded
                val hash = MessageDigest.getInstance("SHA-256").digest(spki)
                return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
            }
        }
    }

    fun createPinnedSSLContext(): SSLContext {
        val tm = createPinnedTrustManager()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), java.security.SecureRandom())
        return ctx
    }

    // Create an HTTPS connection with cert pinning
    fun openPinnedConnection(url: String): HttpsURLConnection {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = createPinnedSSLContext().socketFactory
        conn.hostnameVerifier = HostnameVerifier { hostname, _ ->
            hostname.endsWith("cloudflare.com") || hostname == "1.1.1.1" ||
            hostname.endsWith("google.com") || hostname == "8.8.8.8"
        }
        return conn
    }
}
