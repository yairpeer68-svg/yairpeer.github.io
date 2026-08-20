# Ghost Eye Phone 10.2.2 Full Build Fix

- Enables AndroidX in gradle.properties.
- Aligns Java and Kotlin bytecode targets to JVM 17.
- Keeps Gradle itself on JDK 21.
- Normalizes ApiClient coroutine imports to exactly one Dispatchers and one withContext import.
- Keeps HTTPS API endpoint internal via BuildConfig, not visible in UI.
- Version code 18 / version name 10.2.2.
