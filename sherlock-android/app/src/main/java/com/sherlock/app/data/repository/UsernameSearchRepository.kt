package com.sherlock.app.data.repository

import com.sherlock.app.data.model.ErrorType
import com.sherlock.app.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UsernameSearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun searchUsername(username: String): Flow<SearchResult> = flow {
        val sites = SitesDatabase.sites
        for (site in sites) {
            val url = site.urlTemplate.replace("{}", username)
            val startTime = System.currentTimeMillis()

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .head()
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - startTime

                val exists = when (site.errorType) {
                    ErrorType.STATUS_CODE -> response.code in 200..299
                    ErrorType.MESSAGE_IN_PAGE -> {
                        if (response.code in 200..299) {
                            val getRequest = Request.Builder()
                                .url(url)
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                                .build()
                            val getResponse = client.newCall(getRequest).execute()
                            val body = getResponse.body?.string() ?: ""
                            getResponse.close()
                            !body.contains(site.errorIndicator, ignoreCase = true)
                        } else {
                            false
                        }
                    }
                    ErrorType.REDIRECT -> response.code !in 300..399
                }

                response.close()

                emit(
                    SearchResult(
                        siteName = site.name,
                        url = url,
                        username = username,
                        exists = exists,
                        category = site.category,
                        responseTimeMs = elapsed
                    )
                )
            } catch (_: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                emit(
                    SearchResult(
                        siteName = site.name,
                        url = url,
                        username = username,
                        exists = false,
                        category = site.category,
                        responseTimeMs = elapsed
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)
}
