-keep class io.flutter.** { *; }
-dontwarn io.flutter.embedding.**

# Flutter's embedding references Play Core's deferred-component classes from
# FlutterPlayStoreSplitApplication, but this app does not ship Play Core. Without this
# R8 aborts the release build with:
#   Missing class com.google.android.play.core.splitcompat.SplitCompatApplication
-dontwarn com.google.android.play.core.**
-keep class com.google.android.play.core.** { *; }

# flutter_secure_storage encrypts through Tink/androidx.security, which is reached
# reflectively.
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep the metadata plugin channel codecs rely on.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
