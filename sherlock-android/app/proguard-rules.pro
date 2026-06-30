-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,Exceptions,*Annotation*,InnerClasses,EnclosingMethod

-keep class com.sherlock.app.data.model.** { *; }

-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gson reflection over model classes
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn com.google.gson.**

# ML Kit ships its own consumer rules; silence transitive warnings
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Room consumer rules are bundled in the AAR; keep generated Dao impls just in case
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
