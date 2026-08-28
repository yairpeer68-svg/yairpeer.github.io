# Ghost Eye Phone 11.0.1 — Hardening & Reliability

- Password is no longer stored with `rememberSaveable`.
- Target-watch flow snapshots nullable scan state and removes unsafe `!!` access.
- Sensitive UI is protected with Android `FLAG_SECURE`.
- Gradle 8.7 distribution is SHA-256 pinned.
- Added Android CI for wrapper validation, build, unit tests and lint.
