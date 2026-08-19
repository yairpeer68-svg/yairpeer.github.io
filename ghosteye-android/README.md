# Ghost Eye Phone 10.1.1

Production-oriented single-user Android client for `https://51.20.205.229`.

The real owner email is not embedded in the APK. The UI displays only `******er68`, while password-only owner login is resolved server-side. Registration is absent. Sessions use Android Keystore AES-GCM, token refresh is serialized globally, uploads stream with progress and idempotency, and transient failures are reported accurately instead of being shown as bad passwords.

The app includes Dashboard, analysis, live progress, Risk/Findings/Evidence/AI results, Projects/Cases, History, historical comparison, report signing/verification, diagnostics, dark mode, offline indication, local crash diagnostics and logout.

## Windows build

Run:

```bat
BUILD_PHONE_WINDOWS.bat
```

APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Then, with ADB/USB debugging available, run:

```bat
VERIFY_PHONE_WINDOWS.bat
```

The build script prefers Temurin JDK 21 when available.
