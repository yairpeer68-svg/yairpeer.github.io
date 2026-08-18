package com.ghosteye.intelligence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONObject

class JobSocket(private val baseUrl: String, private val token: String?) {
    private val client = OkHttpClient()
    private val _state = MutableStateFlow<JSONObject?>(null)
    val state: StateFlow<JSONObject?> = _state

    private var socket: WebSocket? = null

    fun connect(jobId: String) {
        val httpBase = baseUrl.removeSuffix("/").replace("https://", "wss://").replace("http://", "ws://")
        val req = Request.Builder().url("$httpBase/api/v1/ws/jobs/$jobId")
            .apply { if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token") }
            .build()
        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess { _state.value = it }
            }
        })
    }

    fun close() {
        socket?.close(1000, "done")
        socket = null
    }
}
