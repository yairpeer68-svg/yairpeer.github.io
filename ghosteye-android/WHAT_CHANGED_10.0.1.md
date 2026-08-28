# Ghost Eye Phone 10.0.2 — Crash hotfix

This build fixes the startup/dashboard crash seen as `Parent job is Cancelling`.

## Fixes
- Dashboard child requests now run under `supervisorScope`; Graph/Audit/Jobs failures cannot cancel the entire screen.
- Coroutine cancellation is treated as lifecycle cancellation and is never rendered as an app error.
- Concurrent 401 responses share a serialized refresh-token rotation path.
- A refresh already completed by another request is reused instead of rotating again.
- Transient refresh/network failures no longer clear the local session or force logout.
- Startup session validation is fail-safe and cannot terminate the Activity on malformed/corrupt state.
- API JSON parsing failures become recoverable UI errors instead of uncaught parser exceptions.
- History removed unsafe `!!` result access.
- Analysis, History, Projects and Settings now propagate lifecycle cancellation correctly.

Server version 10.0.0 remains compatible; no server update is required for this hotfix.
