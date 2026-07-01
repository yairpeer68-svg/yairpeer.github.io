package com.sherlock.app.data

import android.content.Context
import android.net.Uri
import com.sherlock.app.util.BrowserHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ImageSearchRepository(private val context: Context) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionSpecs(listOf(BrowserHeaders.tlsSpec, ConnectionSpec.MODERN_TLS))
            .build()
    }

    suspend fun uploadToYandex(imageUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: return@withContext null

            val profile = BrowserHeaders.randomProfile()
            val headers = BrowserHeaders.headersFor(profile)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "upfile", "image.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val reqBuilder = Request.Builder()
                .url("https://yandex.com/images/search?rpt=imageview&format=json")
                .post(requestBody)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                response.request.url.toString().takeIf { response.code in 200..399 }
            }
        } catch (_: Exception) {
            null
        }
    }
}
