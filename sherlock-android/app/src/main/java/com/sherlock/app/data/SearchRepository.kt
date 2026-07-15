package com.sherlock.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherlock.app.util.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SearchRepository(private val context: Context) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionSpecs(listOf(BrowserHeaders.tlsSpec, ConnectionSpec.MODERN_TLS))
            .build()
    }

    private val sites: List<SiteConfig> by lazy {
        val json = context.assets.open("sites.json").use { it.bufferedReader().readText() }
        val type = object : TypeToken<List<SiteConfig>>() {}.type
        Gson().fromJson<List<SiteConfig>>(json, type)
    }

    fun searchUsername(username: String): Flow<SearchResult> = channelFlow {
        val profile = BrowserHeaders.randomProfile()
        val headers = BrowserHeaders.headersFor(profile)
        val semaphore = Semaphore(20)

        sites.map { site ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val profileUrl = site.url.replace("{}", username)
                    try {
                        send(check(site, username, profileUrl, headers))
                    } catch (e: Exception) {
                        send(SearchResult(site, false, profileUrl, e.message))
                    }
                }
            }
        }.joinAll()
    }

    /** Applies the site's detection strategy from the Sherlock dataset. */
    private fun check(
        site: SiteConfig,
        username: String,
        profileUrl: String,
        headers: Map<String, String>
    ): SearchResult {
        // A username that can't exist on this site is never a hit.
        site.regexCheck?.let { rc ->
            if (!Regex(rc).containsMatchIn(username)) {
                return SearchResult(site, found = false, profileUrl = profileUrl)
            }
        }
        val probeUrl = (site.urlProbe ?: site.url).replace("{}", username)
        return when (site.errorType) {
            "status_code" -> checkStatusCode(site, profileUrl, probeUrl, headers)
            "message" -> checkMessage(site, username, profileUrl, probeUrl, headers)
            "response_url" -> checkResponseUrl(site, username, profileUrl, probeUrl, headers)
            else -> SearchResult(site, found = false, profileUrl = profileUrl)
        }
    }

    private fun get(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private fun checkStatusCode(
        site: SiteConfig,
        profileUrl: String,
        probeUrl: String,
        headers: Map<String, String>
    ): SearchResult {
        val headReq = Request.Builder().url(probeUrl).head()
        headers.forEach { (k, v) -> headReq.header(k, v) }
        val code = client.newCall(headReq.build()).execute().use { r ->
            if (r.code == 405) -1 else r.code
        }
        val finalCode = if (code == -1) {
            client.newCall(get(probeUrl, headers)).execute().use { it.code }
        } else code
        return SearchResult(site, found = finalCode in 200..299, profileUrl = profileUrl)
    }

    private fun checkMessage(
        site: SiteConfig,
        username: String,
        profileUrl: String,
        probeUrl: String,
        headers: Map<String, String>
    ): SearchResult {
        val body = client.newCall(get(probeUrl, headers)).execute().use { it.body?.string() } ?: ""
        val msgs = site.errorMsg.orEmpty()
        val notFound = msgs.any { body.contains(it.replace("{}", username), ignoreCase = true) }
        return SearchResult(site, found = msgs.isNotEmpty() && !notFound, profileUrl = profileUrl)
    }

    private fun checkResponseUrl(
        site: SiteConfig,
        username: String,
        profileUrl: String,
        probeUrl: String,
        headers: Map<String, String>
    ): SearchResult {
        client.newCall(get(probeUrl, headers)).execute().use { r ->
            val finalUrl = r.request.url.toString().trimEnd('/')
            val errorUrl = site.errorUrl?.replace("{}", username)?.trimEnd('/')
            val found = r.isSuccessful && (errorUrl == null || finalUrl != errorUrl)
            return SearchResult(site, found = found, profileUrl = profileUrl)
        }
    }

    fun getSiteCount() = sites.size
}
