# Ghost Eye keeps API payloads in org.json and does not rely on reflection-based
# model serialization. OkHttp/Okio ship their own consumer rules. Keep only
# source/line metadata so production crash IDs remain actionable server-side.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
