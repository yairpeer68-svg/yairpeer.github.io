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
