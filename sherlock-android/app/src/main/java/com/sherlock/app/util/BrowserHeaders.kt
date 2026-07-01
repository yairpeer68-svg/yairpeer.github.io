package com.sherlock.app.util

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion

data class BrowserProfile(
    val userAgent: String,
    val secChUa: String?,
    val secChUaMobile: String,
    val secChUaPlatform: String?,
    val isChrome: Boolean
)

object BrowserHeaders {

    private val profiles = listOf(
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            secChUa = "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            isChrome = true
        ),
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            secChUa = "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"macOS\"",
            isChrome = true
        ),
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.6167.178 Mobile Safari/537.36",
            secChUa = "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            isChrome = true
        ),
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36",
            secChUa = "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            secChUaMobile = "?1",
            secChUaPlatform = "\"Android\"",
            isChrome = true
        ),
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
            secChUa = null,
            secChUaMobile = "?0",
            secChUaPlatform = null,
            isChrome = false
        ),
        BrowserProfile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
            secChUa = "\"Not A(Brand\";v=\"99\", \"Microsoft Edge\";v=\"121\", \"Chromium\";v=\"121\"",
            secChUaMobile = "?0",
            secChUaPlatform = "\"Windows\"",
            isChrome = true
        )
    )

    fun randomProfile(): BrowserProfile = profiles.random()

    fun headersFor(profile: BrowserProfile): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = profile.userAgent
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["Accept-Encoding"] = "gzip, deflate, br"
        h["Upgrade-Insecure-Requests"] = "1"
        h["Cache-Control"] = "max-age=0"
        h["Sec-Fetch-Site"] = "none"
        h["Sec-Fetch-Mode"] = "navigate"
        h["Sec-Fetch-User"] = "?1"
        h["Sec-Fetch-Dest"] = "document"
        if (profile.isChrome) {
            profile.secChUa?.let { h["Sec-Ch-Ua"] = it }
            h["Sec-Ch-Ua-Mobile"] = profile.secChUaMobile
            profile.secChUaPlatform?.let { h["Sec-Ch-Ua-Platform"] = it }
        } else {
            h["DNT"] = "1"
        }
        return h
    }

    // Chrome-ordered cipher suites for TLS 1.2 (TLS 1.3 suites are always enabled by Conscrypt)
    val tlsSpec: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
        )
        .build()
}
