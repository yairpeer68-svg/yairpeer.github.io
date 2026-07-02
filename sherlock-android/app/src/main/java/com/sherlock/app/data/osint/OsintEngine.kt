package com.sherlock.app.data.osint

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherlock.app.util.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A normalized result any OSINT source can emit. Maps 1:1 to a stored finding. */
data class OsintFinding(
    val source: String,
    val category: String,
    val title: String,
    val detail: String = "",
    val url: String = "",
    val positive: Boolean = true
)

/**
 * Read-only OSINT lookups. Every method identifies and reports public information;
 * none of them perform intrusive actions. All work runs on Dispatchers.IO.
 */
class OsintEngine {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionSpecs(listOf(BrowserHeaders.tlsSpec, ConnectionSpec.MODERN_TLS))
            .build()
    }

    private fun request(url: String, accept: String? = null): Request {
        val profile = BrowserHeaders.randomProfile()
        val b = Request.Builder().url(url)
        BrowserHeaders.headersFor(profile).forEach { (k, v) -> b.header(k, v) }
        if (accept != null) b.header("Accept", accept)
        return b.build()
    }

    private fun getBody(url: String, accept: String? = null): Pair<Int, String?> {
        client.newCall(request(url, accept)).execute().use { r ->
            return r.code to r.body?.string()
        }
    }

    // ---------------------------------------------------------------- EMAIL

    suspend fun email(address: String): List<OsintFinding> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OsintFinding>()
        val email = address.trim().lowercase()
        val valid = Regex("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$").matches(email)

        out += OsintFinding(
            source = "Format",
            category = "Email",
            title = if (valid) "Valid email format" else "Invalid email format",
            detail = email,
            positive = valid
        )
        if (!valid) return@withContext out

        val domain = email.substringAfter('@')
        out += OsintFinding("Domain", "Email", "Mail domain", domain, "https://$domain", true)

        if (domain in DISPOSABLE_DOMAINS) {
            out += OsintFinding("Disposable", "Email", "Disposable / temporary email provider", domain, positive = true)
        }

        // Gravatar — a hit reveals a public profile tied to the address.
        try {
            val hash = md5(email)
            val (code, _) = getBody("https://www.gravatar.com/avatar/$hash?d=404")
            if (code == 200) {
                out += OsintFinding(
                    "Gravatar", "Email", "Gravatar profile exists",
                    "Public avatar/profile linked to this email",
                    "https://www.gravatar.com/$hash", true
                )
                try {
                    val (pc, pb) = getBody("https://www.gravatar.com/$hash.json")
                    if (pc == 200 && pb != null) parseGravatar(pb, out)
                } catch (_: Exception) {}
            } else {
                out += OsintFinding("Gravatar", "Email", "No Gravatar profile", positive = false)
            }
        } catch (_: Exception) {}

        // Convenience breach-lookup links (manual verification, no scraping).
        out += OsintFinding(
            "HaveIBeenPwned", "Breach", "Check breaches on HIBP",
            "Open to verify if this address appeared in known breaches",
            "https://haveibeenpwned.com/account/$email", true
        )
        out
    }

    private fun parseGravatar(body: String, out: MutableList<OsintFinding>) {
        val json = JsonParser.parseString(body).asJsonObject
        val entry = json.getAsJsonArray("entry")?.firstOrNull()?.asJsonObject ?: return
        entry.get("preferredUsername")?.asStringOrNull()?.let {
            out += OsintFinding("Gravatar", "Email", "Linked username", it, positive = true)
        }
        entry.get("displayName")?.asStringOrNull()?.let {
            out += OsintFinding("Gravatar", "Email", "Display name", it, positive = true)
        }
        entry.getAsJsonArray("accounts")?.forEach { acc ->
            val o = acc.asJsonObject
            val name = o.get("shortname")?.asStringOrNull() ?: o.get("domain")?.asStringOrNull() ?: "account"
            val url = o.get("url")?.asStringOrNull() ?: ""
            out += OsintFinding("Gravatar", "Email", "Linked account: $name", url, url, true)
        }
    }

    // --------------------------------------------------------------- DOMAIN

    suspend fun domain(input: String): List<OsintFinding> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OsintFinding>()
        val domain = input.trim().lowercase()
            .removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (domain.isBlank()) return@withContext out

        // DNS-over-HTTPS (Cloudflare) — A / AAAA / MX / NS / TXT.
        val types = listOf(1 to "A", 28 to "AAAA", 15 to "MX", 2 to "NS", 16 to "TXT")
        for ((code, label) in types) {
            try {
                val (_, body) = getBody(
                    "https://cloudflare-dns.com/dns-query?name=$domain&type=$label",
                    accept = "application/dns-json"
                )
                if (body != null) {
                    val ans = JsonParser.parseString(body).asJsonObject
                        .getAsJsonArray("Answer") ?: continue
                    ans.forEach { a ->
                        val data = a.asJsonObject.get("data")?.asStringOrNull() ?: return@forEach
                        out += OsintFinding("DNS", "Domain", "$label record", data, positive = true)
                    }
                }
            } catch (_: Exception) {}
        }
        if (out.none { it.source == "DNS" }) {
            out += OsintFinding("DNS", "Domain", "No DNS records resolved", domain, positive = false)
        }

        // RDAP WHOIS — registrar, key dates, status.
        try {
            val (rc, rb) = getBody("https://rdap.org/domain/$domain", accept = "application/rdap+json")
            if (rc == 200 && rb != null) parseRdap(rb, out) else
                out += OsintFinding("RDAP", "Domain", "No WHOIS record", domain, positive = false)
        } catch (_: Exception) {}

        out += OsintFinding(
            "Wayback", "Domain", "Historical snapshots",
            "Browse archived versions of this site",
            "https://web.archive.org/web/*/$domain", true
        )
        out
    }

    private fun parseRdap(body: String, out: MutableList<OsintFinding>) {
        val json = JsonParser.parseString(body).asJsonObject
        json.getAsJsonArray("events")?.forEach { e ->
            val o = e.asJsonObject
            val action = o.get("eventAction")?.asStringOrNull() ?: return@forEach
            val date = o.get("eventDate")?.asStringOrNull() ?: return@forEach
            out += OsintFinding("RDAP", "Domain", "WHOIS: $action", date.take(10), positive = true)
        }
        json.getAsJsonArray("status")?.let { st ->
            val statuses = st.mapNotNull { it.asStringOrNull() }
            if (statuses.isNotEmpty())
                out += OsintFinding("RDAP", "Domain", "Status", statuses.joinToString(", "), positive = true)
        }
        json.getAsJsonArray("entities")?.forEach { ent ->
            val o = ent.asJsonObject
            val roles = o.getAsJsonArray("roles")?.mapNotNull { it.asStringOrNull() } ?: emptyList()
            if ("registrar" in roles) {
                val name = vcardFullName(o)
                if (name != null) out += OsintFinding("RDAP", "Domain", "Registrar", name, positive = true)
            }
        }
    }

    private fun vcardFullName(entity: JsonObject): String? {
        val vcard = entity.getAsJsonArray("vcardArray") ?: return null
        val props = vcard.lastOrNull()?.asJsonArray ?: return null
        props.forEach { p ->
            val arr = p.asJsonArray
            if (arr.size() >= 4 && arr[0].asStringOrNull() == "fn") return arr[3].asStringOrNull()
        }
        return null
    }

    // ------------------------------------------------------------------- IP

    suspend fun ip(input: String): List<OsintFinding> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OsintFinding>()
        val ip = input.trim()
        try {
            val (code, body) = getBody("https://ipwho.is/$ip")
            if (code == 200 && body != null) {
                val json = JsonParser.parseString(body).asJsonObject
                if (json.get("success")?.asBoolean == true) {
                    fun add(title: String, value: String?) {
                        if (!value.isNullOrBlank()) out += OsintFinding("ipwho.is", "IP", title, value, positive = true)
                    }
                    add("Type", json.get("type")?.asStringOrNull())
                    add("Country", json.get("country")?.asStringOrNull())
                    add("Region", json.get("region")?.asStringOrNull())
                    add("City", json.get("city")?.asStringOrNull())
                    val lat = json.get("latitude")?.asStringOrNull()
                    val lon = json.get("longitude")?.asStringOrNull()
                    if (lat != null && lon != null) {
                        out += OsintFinding(
                            "ipwho.is", "IP", "Coordinates", "$lat, $lon",
                            "https://www.google.com/maps?q=$lat,$lon", true
                        )
                    }
                    val conn = json.getAsJsonObject("connection")
                    if (conn != null) {
                        add("ISP", conn.get("isp")?.asStringOrNull())
                        add("Organization", conn.get("org")?.asStringOrNull())
                        conn.get("asn")?.asStringOrNull()?.let {
                            out += OsintFinding("ipwho.is", "IP", "ASN", "AS$it", positive = true)
                        }
                    }
                } else {
                    out += OsintFinding("ipwho.is", "IP", "Lookup failed", ip, positive = false)
                }
            }
        } catch (_: Exception) {
            out += OsintFinding("ipwho.is", "IP", "Lookup error", ip, positive = false)
        }
        out
    }

    // ---------------------------------------------------------------- PHONE

    suspend fun phone(input: String): List<OsintFinding> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OsintFinding>()
        val raw = input.trim()
        val digits = raw.filter { it.isDigit() || it == '+' }
        val normalized = if (digits.startsWith("+")) digits else "+$digits"

        val country = COUNTRY_CODES.entries
            .filter { normalized.startsWith("+" + it.key) }
            .maxByOrNull { it.key.length }

        out += OsintFinding("Format", "Phone", "Normalized number", normalized, positive = true)
        if (country != null) {
            out += OsintFinding("Prefix", "Phone", "Country", "${country.value} (+${country.key})", positive = true)
        } else {
            out += OsintFinding("Prefix", "Phone", "Country not recognized", normalized, positive = false)
        }

        val q = normalized.removePrefix("+")
        out += OsintFinding(
            "Truecaller", "Phone", "Look up on Truecaller",
            "Open to check caller ID / spam reports",
            "https://www.truecaller.com/search/global/$q", true
        )
        out += OsintFinding(
            "WhatsApp", "Phone", "Open WhatsApp chat",
            "Confirms whether the number has WhatsApp",
            "https://wa.me/$q", true
        )
        out
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        try { if (isJsonNull) null else asString } catch (_: Exception) { null }

    companion object {
        private val DISPOSABLE_DOMAINS = setOf(
            "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com",
            "temp-mail.org", "throwawaymail.com", "yopmail.com", "getnada.com",
            "trashmail.com", "sharklasers.com", "maildrop.cc", "dispostable.com"
        )
        private val COUNTRY_CODES = mapOf(
            "972" to "Israel", "1" to "USA/Canada", "44" to "UK", "33" to "France",
            "49" to "Germany", "39" to "Italy", "34" to "Spain", "7" to "Russia",
            "86" to "China", "81" to "Japan", "82" to "South Korea", "91" to "India",
            "61" to "Australia", "55" to "Brazil", "52" to "Mexico", "31" to "Netherlands",
            "46" to "Sweden", "47" to "Norway", "48" to "Poland", "90" to "Turkey",
            "971" to "UAE", "966" to "Saudi Arabia", "20" to "Egypt", "27" to "South Africa",
            "380" to "Ukraine", "40" to "Romania", "30" to "Greece", "351" to "Portugal",
            "41" to "Switzerland", "43" to "Austria", "32" to "Belgium", "45" to "Denmark",
            "358" to "Finland", "353" to "Ireland", "64" to "New Zealand", "65" to "Singapore",
            "60" to "Malaysia", "62" to "Indonesia", "63" to "Philippines", "66" to "Thailand",
            "84" to "Vietnam", "92" to "Pakistan", "880" to "Bangladesh", "98" to "Iran",
            "212" to "Morocco", "213" to "Algeria", "234" to "Nigeria", "254" to "Kenya",
            "56" to "Chile", "57" to "Colombia", "54" to "Argentina", "51" to "Peru"
        )
    }
}
