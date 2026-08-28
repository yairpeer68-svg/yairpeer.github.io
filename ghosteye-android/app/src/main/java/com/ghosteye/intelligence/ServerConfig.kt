package com.ghosteye.intelligence

object ServerConfig {
    val BASE_URL: String = BuildConfig.API_BASE_URL.trimEnd('/')

    // The owner identity is resolved exclusively by the server. Do not package
    // even a masked email fragment in the APK.
    const val OWNER_LABEL: String = "Owner account"
}
