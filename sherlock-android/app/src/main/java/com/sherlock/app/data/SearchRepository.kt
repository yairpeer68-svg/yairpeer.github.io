package com.sherlock.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherlock.app.util.BrowserHeaders
import com.sherlock.app.util.BrowserProfile
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
                    val url = site.url.replace("{}", username)
                    try {
                        val result = when (site.errorType) {
                            "status_code" -> checkByStatusCode(url, site, headers)
                            "message" -> checkByMessage(url, site, headers)
                            else -> SearchResult(site, false, url, "unknown error type")
                        }
                        send(result)
                    } catch (e: Exception) {
                        send(SearchResult(site, false, url, e.message))
                    }
                }
            }
        }.joinAll()
    }

    private fun checkByStatusCode(url: String, site: SiteConfig, headers: Map<String, String>): SearchResult {
        val req = Request.Builder().url(url).head()
        headers.forEach { (k, v) -> req.addHeader(k, v) }
        val response = client.newCall(req.build()).execute()
        val found = if (response.code == 405) {
            // Site doesn't allow HEAD — retry with GET
            response.close()
            val getReq = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> getReq.addHeader(k, v) }
            client.newCall(getReq.build()).execute().use { it.isSuccessful }
        } else {
            response.use { it.isSuccessful }
        }
        return SearchResult(site = site, found = found, profileUrl = url)
    }

    private fun checkByMessage(url: String, site: SiteConfig, headers: Map<String, String>): SearchResult {
        val req = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> req.addHeader(k, v) }
        return client.newCall(req.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            val notFound = site.errorMsg.isNotBlank() && body.contains(site.errorMsg, ignoreCase = true)
            SearchResult(site = site, found = !notFound && response.isSuccessful, profileUrl = url)
        }
    }

    fun getSiteCount() = sites.size
}
