# Ghost Eye Phone 10.1.0 — Stability Release

- Owner email removed from the APK; the UI displays only `******er68`.
- Password-only owner login uses the dedicated single-user server endpoint.
- One shared OkHttp client, globally serialized token refresh and precise HTTP/network errors.
- Uploads are streamed with progress and idempotency keys.
- Offline banner and automatic Dashboard refresh.
- Diagnostics screen: server version, PostgreSQL, Redis, workers, queue and storage.
- Local-only crash record for diagnosis; it is never uploaded automatically and can be cleared in Settings.
- Job cancel and retry controls.
- Signed report generation + verification.
- Historical comparison of completed analyses for the same artifact name.
- Dark mode preference persists across launches.
- Windows build script performs a clean build; Windows verification script launches the APK through ADB and checks for AndroidRuntime crashes.
