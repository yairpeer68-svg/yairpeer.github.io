import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningAvailable = keystorePropertiesFile.exists()

android {
    namespace = "com.ghosteye.intelligence"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ghosteye.intelligence"
        minSdk = 26
        targetSdk = 35
        versionCode = 32
        versionName = "1.1.0"
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

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                val storePath = keystoreProperties.getProperty("storeFile")
                    ?: error("keystore.properties: storeFile is required")
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: error("keystore.properties: storePassword is required")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: error("keystore.properties: keyAlias is required")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: error("keystore.properties: keyPassword is required")
            }
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningAvailable) {
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
