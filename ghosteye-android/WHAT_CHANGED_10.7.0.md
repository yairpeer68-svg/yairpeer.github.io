# Ghost Eye Phone 10.7.0 — Autonomous Production

## Clean UI stays clean

The two main bottom areas remain exactly:
- **קובץ**
- **דומיין**

Investigations are opened from a compact top-bar icon or directly from a completed result; no third bottom tab was added.

## Autonomous investigation UX

- Start a bounded investigation from a completed file or target result.
- File investigations expose a separate explicit permission switch before any extracted public domain/IP is actively scanned. Without permission, pivots remain evidence only.
- Target investigations reuse the explicit authorisation already given on the target-scan screen.
- Investigation list and detail view with phase, progress, risk, Hebrew summary, timeline, entity/link counts and evidence-backed hypotheses.
- Pause/resume/cancel.
- Compare a completed investigation with a previous compatible investigation.
- Create and immediately verify a signed investigation report.
- Delete terminal investigations.
- Raw job IDs are intentionally not presented as the main user-facing information.

## Production Android release

- Version 10.7.0 / versionCode 23.
- Debug Windows build remains available for testing.
- New signed release workflow with R8/resource shrinking.
- `BUILD_RELEASE_WINDOWS.bat` builds a signed release APK and AAB when local `keystore.properties` is present.
- `GENERATE_RELEASE_KEYSTORE_WINDOWS.bat` creates a local release keystore interactively.
- Keystore/password files are never included in the release bundle.
- Visible UI continues to hide the server IP and full owner email.
