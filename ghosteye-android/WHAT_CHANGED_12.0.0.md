# Ghost Eye Phone 12.0.0 — Autonomous Intelligence Core

- Added an Autonomous Intelligence Core card to every investigation.
- Shows Observe / Assist / Bounded policy mode and allows owner-controlled mode changes while the investigation is active.
- Shows deterministic plan revision, remaining node/target-scan budgets and current plan steps.
- Displays tamper-evident evidence-chain integrity and event count.
- Displays cross-job correlation cluster counts.
- ApiClient now supports autonomy, plan, correlations, evidence-chain and explanation endpoints.
- Version bumped to 12.0.0 / versionCode 26.
- Android CI now provisions Gradle 8.7 explicitly, verifies the pinned distribution SHA-256, then runs assembleDebug + unit tests + lint.
- Existing 11.0.1 hardening remains: password uses `remember`, sensitive screens use `FLAG_SECURE`, cleartext traffic is disabled and unsafe `result!!` access is absent.
