# Flutter's embedding is loaded reflectively.
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Play Core is referenced by Flutter's deferred-components support, which this
# app does not use; without these rules R8 fails on the missing classes.
-dontwarn com.google.android.play.core.**

# Keep annotations used by the secure-storage and notification plugins.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
