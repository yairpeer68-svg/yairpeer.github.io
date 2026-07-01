package com.sherlock.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SearchRepository(private val context: Context) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val sites: List<SiteConfig> by lazy {
        val json = context.assets.open("sites.json").use { it.bufferedReader().readText() }
        val type = object : TypeToken<List<SiteConfig>>() {}.type
        Gson().fromJson<List<SiteConfig>>(json, type)
    }

    fun searchUsername(username: String): Flow<SearchResult> = channelFlow {
        val semaphore = Semaphore(20)
        sites.map { site ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val url = site.url.replace("{}", username)
                    try {
                        val result = when (site.errorType) {
                            "status_code" -> checkByStatusCode(url, site)
                            "message" -> checkByMessage(url, site)
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

    private fun checkByStatusCode(url: String, site: SiteConfig): SearchResult {
        val request = Request.Builder()
            .url(url)
            .head()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .build()
        return client.newCall(request).execute().use { response ->
            SearchResult(site = site, found = response.isSuccessful, profileUrl = url)
        }
    }

    private fun checkByMessage(url: String, site: SiteConfig): SearchResult {
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val notFound = site.errorMsg.isNotBlank() && body.contains(site.errorMsg, ignoreCase = true)
            SearchResult(site = site, found = !notFound && response.isSuccessful, profileUrl = url)
        }
    }

    fun getSiteCount() = sites.size
}
