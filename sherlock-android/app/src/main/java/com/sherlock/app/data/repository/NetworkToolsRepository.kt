package com.sherlock.app.data.repository

import com.google.gson.Gson
import com.sherlock.app.data.model.DnsRecordResult
import com.sherlock.app.data.model.HttpHeaderResult
import com.sherlock.app.data.model.IpGeoResult
import com.sherlock.app.data.model.RedirectHop
import com.sherlock.app.data.model.SslCertInfo
import com.sherlock.app.data.model.VpnProxyResult
import com.sherlock.app.data.model.WebsiteSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class NetworkToolsRepository {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    suspend fun getSslCertificate(domain: String): SslCertInfo = withContext(Dispatchers.IO) {
        val cleanDomain = domain.removePrefix("http://").removePrefix("https://").removeSuffix("/").substringBefore("/")
        val url = java.net.URL("https://$cleanDomain")
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        try {
            connection.connect()
            val cert = connection.serverCertificates.firstOrNull() as? X509Certificate
                ?: throw Exception("לא נמצא אישור אבטחה")
            val now = Date()
            val sanDomains = try {
                cert.subjectAlternativeNames?.mapNotNull { entry ->
                    val item = entry as List<*>
                    if (item.size >= 2 && item[0] == 2) item[1] as? String else null
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            SslCertInfo(
                domain = cleanDomain,
                issuer = cert.issuerDN.name,
                subject = cert.subjectDN.name,
                validFrom = dateFormat.format(cert.notBefore),
                validTo = dateFormat.format(cert.notAfter),
                daysUntilExpiry = (cert.notAfter.time - now.time) / (1000 * 60 * 60 * 24),
                isExpired = cert.notAfter.before(now),
                sanDomains = sanDomains
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun getDnsRecords(domain: String): List<DnsRecordResult> = withContext(Dispatchers.IO) {
        val cleanDomain = domain.removePrefix("http://").removePrefix("https://").removeSuffix("/").substringBefore("/")
        val results = mutableListOf<DnsRecordResult>()
        val types = listOf("A", "AAAA", "MX", "TXT", "NS", "CNAME")
        for (type in types) {
            try {
                val request = Request.Builder()
                    .url("https://dns.google/resolve?name=$cleanDomain&type=$type")
                    .build()
                val body = client.newCall(request).execute().use { it.body?.string() } ?: continue
                val json = Gson().fromJson(body, Map::class.java)
                val answers = json["Answer"] as? List<Map<String, Any>> ?: continue
                answers.forEach { answer ->
                    val data = answer["data"]?.toString() ?: return@forEach
                    results.add(DnsRecordResult(type, data))
                }
            } catch (_: Exception) { }
        }
        results
    }

    suspend fun getHttpHeaders(rawUrl: String): HttpHeaderResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(normalizeUrl(rawUrl)).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val headers = response.headers.toMultimap().flatMap { (name, values) -> values.map { name to it } }
            val tech = mutableListOf<String>()
            val serverHeader = response.header("Server").orEmpty()
            val poweredBy = response.header("X-Powered-By").orEmpty()
            if (serverHeader.contains("nginx", true)) tech.add("Nginx")
            if (serverHeader.contains("Apache", true)) tech.add("Apache")
            if (serverHeader.contains("cloudflare", true) || response.header("cf-ray") != null) tech.add("Cloudflare")
            if (poweredBy.contains("PHP", true) || serverHeader.contains("PHP", true)) tech.add("PHP")
            if (poweredBy.contains("ASP.NET", true)) tech.add("ASP.NET")
            if (body.contains("wp-content") || body.contains("wp-includes")) tech.add("WordPress")
            if (body.contains("cdn.shopify.com") || response.header("X-ShopId") != null) tech.add("Shopify")
            if (body.contains("wixstatic") || body.contains("wix.com")) tech.add("Wix")
            if (body.contains("squarespace")) tech.add("Squarespace")
            if (body.contains("__NEXT_DATA__")) tech.add("Next.js")
            if (body.contains("ng-version")) tech.add("Angular")
            if (body.contains("react")) tech.add("React")
            if (body.contains("google-analytics.com") || body.contains("gtag(")) tech.add("Google Analytics")
            if (body.contains("jquery", true)) tech.add("jQuery")
            HttpHeaderResult(
                statusCode = response.code,
                headers = headers,
                detectedTech = tech.distinct()
            )
        } catch (e: Exception) {
            throw Exception("שגיאה בבדיקת הכתובת: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun getWebsiteSnapshot(rawUrl: String): WebsiteSnapshot = withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeUrl(rawUrl)
            val request = Request.Builder().url(normalized).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
            val description = Regex(
                "<meta[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']*)[\"']",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)
            val faviconHref = Regex(
                "<link[^>]*rel=[\"'](?:shortcut )?icon[\"'][^>]*href=[\"']([^\"']*)[\"']",
                RegexOption.IGNORE_CASE
            ).find(body)?.groupValues?.get(1)
            val finalUrl = response.request.url.toString()
            val baseUrl = finalUrl.toHttpUrlOrNull()
            val faviconUrl = when {
                faviconHref == null -> baseUrl?.let { "${it.scheme}://${it.host}/favicon.ico" }
                faviconHref.startsWith("http") -> faviconHref
                baseUrl != null -> baseUrl.resolve(faviconHref)?.toString()
                else -> null
            }
            WebsiteSnapshot(
                finalUrl = finalUrl,
                title = title,
                description = description,
                faviconUrl = faviconUrl,
                redirected = finalUrl.trimEnd('/') != normalized.trimEnd('/')
            )
        } catch (e: Exception) {
            throw Exception("שגיאה בטעינת האתר: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun getMyPublicIp(): IpGeoResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("http://ip-api.com/json/").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("תגובה ריקה")
            val json = Gson().fromJson(body, Map::class.java)
            IpGeoResult(
                ip = json["query"]?.toString() ?: "",
                country = json["country"]?.toString() ?: "Unknown",
                city = json["city"]?.toString() ?: "Unknown",
                region = json["regionName"]?.toString() ?: "Unknown",
                isp = json["isp"]?.toString() ?: "Unknown",
                lat = (json["lat"] as? Double) ?: 0.0,
                lon = (json["lon"] as? Double) ?: 0.0,
                timezone = json["timezone"]?.toString() ?: "Unknown"
            )
        } catch (e: Exception) {
            throw Exception("שגיאה באיתור כתובת ה-IP: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun expandRedirectChain(rawUrl: String): List<RedirectHop> = withContext(Dispatchers.IO) {
        try {
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val hops = mutableListOf<RedirectHop>()
            var currentUrl = normalizeUrl(rawUrl)
            var hopCount = 0
            while (hopCount < 10) {
                val request = Request.Builder().url(currentUrl).build()
                val response = noRedirectClient.newCall(request).execute()
                hops.add(RedirectHop(currentUrl, response.code))
                if (response.code in 300..399) {
                    val location = response.header("Location") ?: break
                    val resolved = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                    response.close()
                    currentUrl = resolved
                    hopCount++
                } else {
                    response.close()
                    break
                }
            }
            hops
        } catch (e: Exception) {
            throw Exception("שגיאה במעקב הפניות: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun checkVpnProxyHosting(ip: String): VpnProxyResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://ip-api.com/json/$ip?fields=status,message,org,as,proxy,hosting,mobile,query")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("תגובה ריקה")
            val json = Gson().fromJson(body, Map::class.java)
            if (json["status"]?.toString() == "fail") {
                throw Exception(json["message"]?.toString() ?: "כתובת IP לא תקינה")
            }
            VpnProxyResult(
                ip = json["query"]?.toString() ?: ip,
                org = json["org"]?.toString().takeUnless { it.isNullOrBlank() } ?: "לא ידוע",
                asn = json["as"]?.toString().takeUnless { it.isNullOrBlank() } ?: "לא ידוע",
                isProxy = (json["proxy"] as? Boolean) ?: false,
                isHosting = (json["hosting"] as? Boolean) ?: false,
                isMobile = (json["mobile"] as? Boolean) ?: false
            )
        } catch (e: Exception) {
            throw Exception(e.message ?: "שגיאה בבדיקת ה-IP")
        }
    }
}
