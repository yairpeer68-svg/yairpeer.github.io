MAGEN v4.2.0 FULL HARDENING - WINDOWS APK BUILD
================================================

Run: BUILD_APK_ON_WINDOWS.bat

Recommended prerequisites:
- Android Studio / Android SDK
- JDK 21 or 17. The builder intentionally avoids Java 25.

The builder:
- locates Android SDK and JDK 21/17
- ensures Android API 36 + Build Tools 36.0.0
- downloads pinned visual model release 1.1.0 when missing
- verifies GitHub asset digest when available and creates/checks a local model SHA256 lock
- injects model SHA256 for Android runtime verification
- runs verify.py --strict (when Python is installed)
- runs Gradle unit tests
- runs assembleDebug
- verifies APK signature
- writes APK SHA256

Output:
  magen-v4.2.0-full-hardening-debug.apk
  magen-v4.2.0-full-hardening-debug.apk.sha256

Debug APK is for physical-device testing. Production/release builds require your own release keystore and certificate fingerprint; see SECURITY_BUILD.md.
