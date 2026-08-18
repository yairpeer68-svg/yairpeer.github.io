package com.ghosteye.intelligence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import org.json.JSONObject

class ApiClient(private val context: Context, private val baseUrl: String) {
    private val client = OkHttpClient.Builder().build()
    private val session = SessionStore(context)
    private val auth = AuthClient(context, baseUrl)

    private fun signed(builder: Request.Builder): Request {
        val access = session.access()
        if (!access.isNullOrBlank()) builder.header("Authorization", "Bearer $access")
        return builder.build()
    }

    private suspend fun call(build: () -> Request.Builder): Response = withContext(Dispatchers.IO) {
        var response = client.newCall(signed(build())).execute()
        if (response.code == 401) {
            response.close()
            if (auth.refresh()) response = client.newCall(signed(build())).execute()
        }
        response
    }

    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "upload.bin"

        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: error("Unable to open selected file")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body).build()

        call { Request.Builder().url("$baseUrl/api/v1/files").post(multipart) }.use { r ->
            if (!r.isSuccessful) error("Upload failed: ${r.code}")
            JSONObject(requireNotNull(r.body).string()).getString("job_id")
        }
    }

    suspend fun status(jobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v1/jobs/$jobId") }.use { r ->
            if (!r.isSuccessful) error("Status failed: ${r.code}")
            JSONObject(requireNotNull(r.body).string())
        }

    suspend fun result(jobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v1/jobs/$jobId/result") }.use { r ->
            if (!r.isSuccessful) error("Result failed: ${r.code}")
            JSONObject(requireNotNull(r.body).string())
        }

    suspend fun dashboardSummary(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v1/dashboard/summary") }.use { r ->
            if (!r.isSuccessful) error("Dashboard failed: ${r.code}")
            JSONObject(requireNotNull(r.body).string())
        }
}
