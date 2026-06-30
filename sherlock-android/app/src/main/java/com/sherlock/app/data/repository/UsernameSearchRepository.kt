package com.sherlock.app.data.repository

import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.model.SiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UsernameSearchRepository(
    private val parallelThreads: Int = 10,
    private val timeoutSeconds: Long = 10
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun search(query: String, searchType: SearchType): Flow<SearchResult> = channelFlow {
        val sites = when (searchType) {
            SearchType.USERNAME -> SitesDatabase.sites
            SearchType.EMAIL -> SitesDatabase.emailSites
            SearchType.PHONE -> SitesDatabase.phoneSites
            SearchType.FULL_NAME -> SitesDatabase.sites
            else -> SitesDatabase.sites
        }

        val semaphore = Semaphore(parallelThreads)

        coroutineScope {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        val result = checkSite(site, query)
                        send(result)
                    }
                }
            }.awaitAll()
        }
    }.flowOn(Dispatchers.IO)

    fun searchWithVariations(username: String): Flow<SearchResult> = channelFlow {
        val variations = SitesDatabase.generateVariations(username)
        val semaphore = Semaphore(parallelThreads)
        val checked = mutableSetOf<String>()

        coroutineScope {
            for (variation in variations) {
                for (site in SitesDatabase.sites) {
                    val key = "${site.name}:$variation"
                    if (key in checked) continue
                    checked.add(key)

                    async {
                        semaphore.withPermit {
                            val result = checkSite(site, variation)
                            if (result.exists) {
                                send(result)
                            }
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    internal fun checkSite(site: SiteConfig, query: String): SearchResult {
        val url = site.urlTemplate.replace("{}", query)
        val startTime = System.currentTimeMillis()

        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENTS.random())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")

            site.headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            val request = requestBuilder.head().build()
            val response = client.newCall(request).execute()
            val elapsed = System.currentTimeMillis() - startTime
            val statusCode = response.code

            val exists = when (site.errorType) {
                ErrorType.STATUS_CODE -> statusCode in 200..299
                ErrorType.MESSAGE_IN_PAGE -> {
                    if (statusCode in 200..299) {
                        val getRequest = Request.Builder()
                            .url(url)
                            .header("User-Agent", USER_AGENTS.random())
                            .build()
                        val getResponse = client.newCall(getRequest).execute()
                        val body = getResponse.body?.string() ?: ""
                        getResponse.close()
                        !body.contains(site.errorIndicator, ignoreCase = true)
                    } else false
                }
                ErrorType.REDIRECT -> statusCode !in 300..399
                ErrorType.JSON_FIELD -> {
                    val getRequest = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENTS.random())
                        .header("Accept", "application/json")
                        .build()
                    val getResponse = client.newCall(getRequest).execute()
                    val body = getResponse.body?.string() ?: ""
                    getResponse.close()
                    !body.contains(site.errorIndicator, ignoreCase = true) && statusCode in 200..299
                }
            }

            response.close()

            SearchResult(
                siteName = site.name,
                url = url,
                username = query,
                exists = exists,
                category = site.category,
                responseTimeMs = elapsed,
                httpStatus = statusCode
            )
        } catch (_: Exception) {
            SearchResult(
                siteName = site.name,
                url = url,
                username = query,
                exists = false,
                category = site.category,
                responseTimeMs = System.currentTimeMillis() - startTime,
                httpStatus = 0
            )
        }
    }

    companion object {
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
    }
}
