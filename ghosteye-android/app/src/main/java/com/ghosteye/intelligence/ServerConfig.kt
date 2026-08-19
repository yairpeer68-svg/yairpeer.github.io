package com.ghosteye.intelligence

object ServerConfig {
    val BASE_URL: String = BuildConfig.API_BASE_URL.trimEnd('/')

    // UI-only masked label. The real owner email is intentionally not packaged
    // in the APK; the server resolves the single configured owner internally.
    const val OWNER_EMAIL_MASKED: String = "******er68"
}
