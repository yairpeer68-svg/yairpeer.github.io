import java.util.Properties
import java.io.FileInputStream

plugins { id("com.android.application"); id("kotlin-android"); id("dev.flutter.flutter-gradle-plugin") }
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) { FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) } }

android {
    namespace = "com.aiplatform.app"
    compileSdk = flutter.compileSdkVersion
    // file_picker, flutter_secure_storage, path_provider_android and
    // flutter_plugin_android_lifecycle all require 27.x; the Flutter default is 26.x and
    // the build warns on every run. NDK releases are backward compatible.
    ndkVersion = "27.0.12077973"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = JavaVersion.VERSION_17.toString() }
    defaultConfig { applicationId = "com.aiplatform.app"; minSdk = 23; targetSdk = flutter.targetSdkVersion; versionCode = flutter.versionCode; versionName = flutter.versionName }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }
    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Without key.properties a release build silently falls back to the debug
                // signing key, producing an artifact that cannot be published or upgraded.
                // Set ALLOW_UNSIGNED_RELEASE=true only for a throwaway local build.
                val allowUnsigned = (project.findProperty("ALLOW_UNSIGNED_RELEASE") as String?)?.toBoolean() ?: false
                if (!allowUnsigned) {
                    throw GradleException(
                        "android/key.properties is missing. Create it from key.properties.example, " +
                        "or pass -PALLOW_UNSIGNED_RELEASE=true to accept a debug-signed release build."
                    )
                }
                logger.warn("Release build is debug-signed: ALLOW_UNSIGNED_RELEASE=true")
            }
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
flutter { source = "../.." }
