# Build Report — 2.1.1

Date: 2026-08-21.

## Component status

- **Backend:** complete. `ruff`, `mypy` and 95 unit tests all pass in this environment. This release fixes the archive decompression-bomb path, adds per-account workspace/project/run ceilings and a run timeout, claims runs under a row lock, replaces the whole-workspace rehash on every code search with a stat fingerprint, and schedules the retention sweep that was previously never enqueued.
- **Runner:** complete. Constant-time token comparison, inline-code and Git-override flags refused for allow-listed interpreters, process-group kill on timeout, and CPU/file-size limits. `runner/Dockerfile.flutter` is an opt-in image carrying the Flutter/Dart/Android toolchain.
- **Admin:** complete and, for the first time, actually built. The console is served correctly from `/admin/`, ships a lockfile, and gained working project/archive-import and approve/reject controls in place of read-only JSON.
- **Android:** complete and verified with Flutter 3.27.1 — `pub get`, `dart format`, `flutter analyze` and `flutter test` all pass. Dependency resolution was previously broken. The client gained ZIP import, SPKI certificate pinning, persisted theme/language, a single version constant, and full Hebrew/English coverage across all screens.
- **Deployment:** Nginx now accepts archive uploads (the 2 MB body limit rejected every import), repeats security headers inside each `location`, and a `scheduler` service applies retention. Backups capture the engineering workspace volume alongside the database.

See `TEST_REPORT.md` for the exact commands, results and limitations. No unexecuted check is represented as a pass.
