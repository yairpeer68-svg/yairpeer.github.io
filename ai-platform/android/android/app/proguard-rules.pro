-keep class io.flutter.** { *; }
-dontwarn io.flutter.embedding.**

# flutter_secure_storage and file_picker reach platform channels reflectively.
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep annotation metadata used by plugin channel codecs.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
