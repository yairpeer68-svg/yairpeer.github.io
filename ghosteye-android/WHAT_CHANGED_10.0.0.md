# Ghost Eye Phone 10.0.0

## Login reliability
- One owner email is displayed and used automatically; no email typo path.
- Correct login is a single user action.
- Transient 502/503/504 and network I/O failures retry internally.
- 401, 429, network failures, server failures and token-storage failures have distinct UI messages.
- Session tokens are persisted atomically before navigation.
- New Android Keystore alias `ghost-eye-session-v10` prevents legacy encrypted-state corruption.
- Old session state is cleared automatically once on upgrade.
- Existing session is validated against `/health` on app startup and refreshed when needed.

## UI/UX rebuild
- Ghost Eye branding and dark-first theme.
- RTL Hebrew layout.
- Dashboard with live job metrics, graph preview, recent jobs and audit activity.
- New analysis flow with Android file picker, streaming upload, live stage/progress and intelligence result.
- Risk, findings, evidence and AI summary presentation.
- Functional Projects/Cases screen.
- Functional History screen with completed result details.
- Settings with server health, dark-mode toggle and logout.
- No registration UI and no placeholder Graph/Audit/Projects pages.

## Build reliability
- Debug and release builds are HTTPS-only.
- Windows build script creates an installable debug APK.
- Windows build script prefers Temurin JDK 21 so JDK 25 in PATH does not break Gradle/AGP.
