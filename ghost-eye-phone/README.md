# Ghost Eye Phone 10.0.1

Single-user Android client for `https://51.20.205.229`.

Key behavior:
- One owner account only: `yairpeer68@gmail.com`.
- No registration screen or registration client flow.
- A correct login is one user action; transient network/server failures retry internally.
- HTTP 401, 429, server failures and network failures are shown as different messages.
- Session storage uses a new Android Keystore v10 key and atomic token persistence.
- Stale/corrupt sessions are cleared automatically.
- HTTPS only in debug and release builds.
- Dashboard, new analysis, live progress, intelligence results, projects/cases, history and server settings are functional UI screens.

## Windows build

Run:

```bat
BUILD_PHONE_WINDOWS.bat
```

Installable APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

The Windows build script automatically prefers Temurin JDK 21 when it is installed under `C:\Program Files\Eclipse Adoptium\jdk-21*`, avoiding incompatibilities when JDK 25 is first in PATH.
