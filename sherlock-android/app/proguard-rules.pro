-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.sherlock.app.data.model.** { *; }

-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
