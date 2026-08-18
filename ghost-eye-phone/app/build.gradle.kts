plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.ghosteye.intelligence"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ghosteye.intelligence"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "10.0.1"
    }
    signingConfigs {
        create("release") {
            val ksPath = providers.gradleProperty("GHOSTEYE_KEYSTORE").orNull
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = providers.gradleProperty("GHOSTEYE_KS_PASS").get()
                keyAlias = providers.gradleProperty("GHOSTEYE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("GHOSTEYE_KEY_PASS").get()
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    buildTypes {
        debug {
            val apiBase = providers.gradleProperty("API_BASE_URL").orElse("https://51.20.205.229").get()
            require(apiBase.startsWith("https://")) { "Debug API_BASE_URL must use HTTPS" }
            buildConfigField("String", "API_BASE_URL", "\"${apiBase.replace("\"", "\\\"")}\"")
        }
        release {
            val apiBase = providers.gradleProperty("API_BASE_URL").orElse("https://51.20.205.229").get()
            require(apiBase.startsWith("https://")) { "Release API_BASE_URL must use HTTPS" }
            buildConfigField("String", "API_BASE_URL", "\"${apiBase.replace("\"", "\\\"")}\"")
            if (providers.gradleProperty("GHOSTEYE_KEYSTORE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
