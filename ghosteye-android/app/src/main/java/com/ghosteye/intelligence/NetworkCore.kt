package com.ghosteye.intelligence

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object GhostEyeHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
