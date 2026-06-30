package com.sherlock.app.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherlock.app.data.model.BreachInfo
import com.sherlock.app.data.model.ExposureReport
import com.sherlock.app.data.model.HashIdentification
import com.sherlock.app.data.model.PasteResult
import com.sherlock.app.data.model.WaybackAvailability
import com.sherlock.app.data.model.WaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OsintToolsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkBreaches(email: String, apiKey: String): List<BreachInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw Exception("יש להזין מפתח API של HaveIBeenPwned בהגדרות כדי לבדוק דליפות מידע")
        }
        try {
            val request = Request.Builder()
                .url("https://haveibeenpwned.com/api/v3/breachedaccount/${URLEncoder.encode(email, "UTF-8")}?truncateResponse=false")
                .addHeader("hibp-api-key", apiKey)
                .addHeader("User-Agent", "Sherlock-Android-App")
                .build()
            val response = client.newCall(request).execute()
            when (response.code) {
                404 -> return@withContext emptyList()
                401 -> throw Exception("מפתח API שגוי")
                429 -> throw Exception("יותר מדי בקשות, נסה שוב מאוחר יותר")
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = Gson().fromJson(body, type)
            list.map { item ->
                BreachInfo(
                    name = item["Name"]?.toString() ?: "",
                    domain = item["Domain"]?.toString() ?: "",
                    breachDate = item["BreachDate"]?.toString() ?: "",
                    dataClasses = (item["DataClasses"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                    description = item["Description"]?.toString()?.replace(Regex("<[^>]*>"), "") ?: ""
                )
            }
        } catch (e: Exception) {
            throw Exception(e.message ?: "שגיאה בבדיקת דליפות מידע")
        }
    }

    private val commonHashes: Map<String, String> by lazy { buildCommonHashDictionary() }

    fun identifyHash(hash: String): HashIdentification {
        val clean = hash.trim()
        val types = mutableListOf<String>()
        when {
            clean.matches(Regex("^[a-fA-F0-9]{32}$")) -> types.addAll(listOf("MD5", "NTLM", "MD4"))
            clean.matches(Regex("^[a-fA-F0-9]{40}$")) -> types.add("SHA-1")
            clean.matches(Regex("^[a-fA-F0-9]{56}$")) -> types.add("SHA-224")
            clean.matches(Regex("^[a-fA-F0-9]{64}$")) -> types.add("SHA-256")
            clean.matches(Regex("^[a-fA-F0-9]{96}$")) -> types.add("SHA-384")
            clean.matches(Regex("^[a-fA-F0-9]{128}$")) -> types.add("SHA-512")
            clean.startsWith("\$2a\$") || clean.startsWith("\$2b\$") || clean.startsWith("\$2y\$") -> types.add("bcrypt")
            clean.startsWith("\$1\$") -> types.add("MD5 Crypt")
            clean.startsWith("\$5\$") -> types.add("SHA-256 Crypt")
            clean.startsWith("\$6\$") -> types.add("SHA-512 Crypt")
            else -> types.add("לא זוהה")
        }
        val known = commonHashes[clean.lowercase()]
        return HashIdentification(hash = clean, possibleTypes = types, knownPlaintext = known)
    }

    private fun buildCommonHashDictionary(): Map<String, String> {
        val commonPasswords = listOf(
            "123456", "password", "12345678", "qwerty", "123456789", "12345", "1234", "111111",
            "1234567", "dragon", "123123", "baseball", "abc123", "football", "monkey", "letmein",
            "shadow", "master", "666666", "qwertyuiop", "123321", "mustang", "1234567890", "michael",
            "654321", "superman", "1qaz2wsx", "7777777", "121212", "000000", "qazwsx", "123qwe",
            "killer", "trustno1", "jordan", "jennifer", "zxcvbnm", "asdfgh", "hunter", "buster",
            "soccer", "harley", "batman", "andrew", "tigger", "sunshine", "iloveyou", "2000",
            "charlie", "robert", "thomas", "hockey", "ranger", "daniel", "starwars", "112233",
            "george", "computer", "michelle", "jessica", "pepper", "1111", "zxcvbn", "555555",
            "11111111", "131313", "freedom", "777777", "pass", "maggie", "159753", "aaaaaa",
            "ginger", "princess", "joshua", "cheese", "amanda", "summer", "love", "ashley",
            "6969", "nicole", "chelsea", "matthew", "access", "yankees", "987654321", "dallas",
            "austin", "thunder", "taylor", "matrix", "admin", "welcome", "passw0rd", "test"
        )
        val map = mutableMapOf<String, String>()
        commonPasswords.forEach { pwd ->
            map[hashWith("MD5", pwd)] = pwd
            map[hashWith("SHA-1", pwd)] = pwd
            map[hashWith("SHA-256", pwd)] = pwd
        }
        return map
    }

    private fun hashWith(algorithm: String, text: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun searchPastes(query: String): List<PasteResult> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://psbdmp.ws/api/v3/search/${URLEncoder.encode(query, "UTF-8")}")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = Gson().fromJson(body, Map::class.java)
            val data = json["data"] as? List<Map<String, Any>> ?: return@withContext emptyList()
            data.map { item ->
                PasteResult(
                    id = item["id"]?.toString() ?: "",
                    text = item["text"]?.toString().orEmpty(),
                    date = item["date"]?.toString().orEmpty()
                )
            }
        } catch (e: Exception) {
            throw Exception("שגיאה בחיפוש באתרי Paste: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun checkWaybackAvailability(url: String): WaybackAvailability = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://archive.org/wayback/available?url=${URLEncoder.encode(url, "UTF-8")}")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("תגובה ריקה")
            val json = Gson().fromJson(body, Map::class.java)
            val snapshots = json["archived_snapshots"] as? Map<String, Any>
            val closest = snapshots?.get("closest") as? Map<String, Any>
            WaybackAvailability(
                available = closest != null,
                url = closest?.get("url")?.toString(),
                timestamp = closest?.get("timestamp")?.toString()
            )
        } catch (e: Exception) {
            throw Exception("שגיאה בבדיקת Wayback Machine: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun getWaybackHistory(url: String, limit: Int = 30): List<WaybackSnapshot> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://web.archive.org/cdx/search/cdx?url=${URLEncoder.encode(url, "UTF-8")}&output=json&limit=$limit&collapse=timestamp:8")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val type = object : TypeToken<List<List<String>>>() {}.type
            val rows: List<List<String>> = Gson().fromJson(body, type)
            if (rows.size <= 1) return@withContext emptyList()
            rows.drop(1).mapNotNull { row ->
                if (row.size < 3) return@mapNotNull null
                WaybackSnapshot(timestamp = row[1], url = "https://web.archive.org/web/${row[1]}/${row[2]}")
            }
        } catch (e: Exception) {
            throw Exception("שגיאה בטעינת היסטוריית ארכיון: ${e.message ?: "שגיאת רשת"}")
        }
    }

    suspend fun buildExposureReport(query: String, isEmail: Boolean, hibpApiKey: String): ExposureReport = withContext(Dispatchers.IO) {
        val hibpChecked = isEmail && hibpApiKey.isNotBlank()
        val breaches = if (hibpChecked) {
            try { checkBreaches(query, hibpApiKey) } catch (_: Exception) { emptyList() }
        } else emptyList()
        val pastes = try { searchPastes(query) } catch (_: Exception) { emptyList() }
        ExposureReport(
            query = query,
            isEmail = isEmail,
            hibpChecked = hibpChecked,
            breaches = breaches,
            pastes = pastes
        )
    }
}
