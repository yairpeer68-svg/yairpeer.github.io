# Ghost Eye Phone 11.1.0 — Case Workspace + Intelligence Automation

Android client for the Ghost Eye single-user intelligence server.

## Main UI

The visible primary navigation intentionally remains simple:

- **קובץ** — file intelligence for APK/AAB/DEX/EXE/DLL/ELF/ZIP/JAR/PDF and other supported artifacts.
- **דומיין** — authorised Domain/URL/public-IP deep intelligence with one **סרוק הכל** action.

A compact **חקירות** icon in the top bar opens Autonomous Investigations without adding another bottom category. The server address and full owner email are not shown in the visible UI.

## Autonomous Investigations

After a completed analysis, choose **פתח חקירה אוטומטית**. Ghost Eye correlates the source analysis with bounded related evidence and, only when explicitly authorised, public network targets. The investigation view provides progress, risk, a Hebrew summary, timeline, graph counts, evidence-backed hypotheses, pause/resume/cancel, comparison and a signed verified report.

Private/loopback/link-local/reserved targets remain blocked by the server. File-derived network pivots require a dedicated authorisation toggle before any active network scan.

## Windows debug build

```bat
BUILD_PHONE_WINDOWS.bat
```

Output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Gradle runtime: JDK 21. Java/Kotlin bytecode target: JVM 17.

## Signed production build

Keep signing material only on your trusted Windows machine.

```bat
GENERATE_RELEASE_KEYSTORE_WINDOWS.bat
copy keystore.properties.example keystore.properties
notepad keystore.properties
BUILD_RELEASE_WINDOWS.bat
```

Outputs:

```text
app\build\outputs\apk\release\app-release.apk
app\build\outputs\bundle\release\app-release.aab
```

Do not share `release-keystore.jks`, `keystore.properties` or the signing passwords.

Version: **11.1.0** (`versionCode 25`).
