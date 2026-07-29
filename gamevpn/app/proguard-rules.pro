# Keep all JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep PacketEngine and its native interface
-keep class com.yourname.gamemodevpn.PacketEngine { *; }

# Keep all Kotlin data classes used for serialization
-keep class com.yourname.gamemodevpn.SessionRecord { *; }
-keep class com.yourname.gamemodevpn.GameProfile { *; }
-keep class com.yourname.gamemodevpn.GameServer { *; }
-keep class com.yourname.gamemodevpn.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Android service and receiver entry points
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.net.VpnService
-keep public class * extends android.service.quicksettings.TileService
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.appwidget.AppWidgetProvider

# Keep the custom views
-keep class com.yourname.gamemodevpn.PingGraphView { *; }
-keep class com.yourname.gamemodevpn.HeatmapView { *; }
-keep class com.yourname.gamemodevpn.AnimatedPowerButton { *; }
-keep class com.yourname.gamemodevpn.ResourceOverlayView { *; }

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Suppress warnings for known safe suppressions
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Security Crypto / EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# WireGuard config serialization
-keep class com.yourname.gamemodevpn.WireGuardManager$WgConfig { *; }

# Play Integrity
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**

# Wear OS DataLayer
-keep class com.google.android.gms.wearable.** { *; }
-dontwarn com.google.android.gms.wearable.**

# DoT/DoH: keep DNS resolver classes intact
-keep class com.yourname.gamemodevpn.DoTResolver { *; }
-keep class com.yourname.gamemodevpn.DoHResolver { *; }
-keep class com.yourname.gamemodevpn.CertPinner { *; }
