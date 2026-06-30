package com.sherlock.app.data.model

data class SslCertInfo(
    val domain: String,
    val issuer: String,
    val subject: String,
    val validFrom: String,
    val validTo: String,
    val daysUntilExpiry: Long,
    val isExpired: Boolean,
    val sanDomains: List<String>
)

data class DnsRecordResult(
    val type: String,
    val value: String
)

data class HttpHeaderResult(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val detectedTech: List<String>
)

data class WebsiteSnapshot(
    val finalUrl: String,
    val title: String?,
    val description: String?,
    val faviconUrl: String?,
    val redirected: Boolean
)

data class RedirectHop(
    val url: String,
    val statusCode: Int
)

data class VpnProxyResult(
    val ip: String,
    val org: String,
    val asn: String,
    val isProxy: Boolean,
    val isHosting: Boolean,
    val isMobile: Boolean
)
