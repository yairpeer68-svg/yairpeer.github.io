# Changelog

## 2.1.1 — 2026-08-21

Corrective release. Several shipped gates had never been executed and were failing; the
flagship engineering runtime could not be driven end to end in a container deployment.

### Fixed — deployment blockers
- Nginx capped every request body at 2 MB while the API accepted 100 MB archives, so project
  import — the only entry point to the engineering runtime — was rejected at the gateway.
  The archive route now uses `ENGINEERING_MAX_ARCHIVE_MB`, and CI asserts the two stay in sync.
- The admin console is served from `/admin/` but was built without a matching `base`, so every
  asset 404'd and the page rendered blank in the container deployment.
- Flutter and Gradle gates are emitted as required while the stock runner image ships neither
  toolchain, so every Flutter/Android project failed by construction. Missing toolchains are now
  reported as `toolchain_missing` and are non-blocking unless `ENGINEERING_STRICT_TOOLCHAINS=true`;
  `runner/Dockerfile.flutter` is an opt-in image carrying the full toolchain.
- The Android client could create projects and start runs but had no way to import source, so
  runs operated on an empty workspace. Added ZIP import to the client and to the admin console.

### Fixed — security
- Archive extraction was budgeted against the attacker-controlled `file_size` header rather than
  bytes written, allowing a decompression bomb to exhaust the volume.
- The command allow-list checked only the executable name, so `python -c`, `node -e`, `node -r`
  and `git -c core.gitProxy=…` passed policy. Inline-code and Git-override flags are now refused.
- The runner compared its bearer token with `!=` instead of a constant-time comparison.
- `--forwarded-allow-ips=*` allowed `X-Forwarded-For` spoofing from any peer reaching the API.
- Account deletion left the user's imported source on disk permanently.
- Added per-account project, active-run, workspace-storage and run-timeout ceilings.
- Retention windows were configured but never applied — no scheduler ever enqueued the sweep.
- Unmatched request paths became Prometheus labels, allowing unbounded metric cardinality.
- Concurrent workers could claim the same run; it is now claimed with `FOR UPDATE SKIP LOCKED`.
- `add_header` inside `location /admin/` silently dropped the server-level security headers.
- Read timeouts to the AI provider were retried, risking a double-billed completion.

### Fixed — correctness and CI
- `ruff check` reported 451 findings and `mypy app` 24 errors; both gates had been red since
  they were added. All correctness findings are fixed and the style-only rules are excluded
  explicitly rather than left permanently failing.
- `pytest tests/unit` could not import the application without a manual `PYTHONPATH`; the CI job
  did not set one. `pyproject.toml` now declares `pythonpath`.
- `npm run build` failed on two TypeScript errors and had never succeeded.
- `flutter pub get` could not resolve: the declared-but-unused `intl` constraint conflicted with
  `flutter_localizations`. `dart format --set-exit-if-changed` and `flutter analyze` also failed.
- Committed `package-lock.json` and `pubspec.lock`; CI uses `npm ci`.
- The release workflow built a debug APK; it now builds a signed release APK and AAB.
- Backups captured only PostgreSQL, leaving the engineering workspace volume — user source and
  the Git history that checkpoint rollback depends on — unrecoverable. `restore.sh` also failed
  to stop writers before `dropdb`.
- The code index re-hashed every byte of the workspace on every search; it now uses a stat
  fingerprint.
- Replaced the N+1 query in the feature-flag endpoint with two queries.
- The Android update gate, device registration and About screen each hard-coded `2.0.0` while the
  app shipped `2.1.0`, so a correctly configured minimum version could lock users out of a current
  build. All three now read one constant, and CI asserts it matches `pubspec.yaml`.
- Certificate pinning pinned the leaf certificate, which breaks on every Let's Encrypt renewal.
  It now pins the SPKI, still accepting an existing leaf pin for migration.
- Theme and language selections were never persisted.
- Seven of thirteen Android screens were English-only in an app that defaults to Hebrew with RTL.
- A release build without `key.properties` was silently signed with the debug key.

### Added
- Approve/reject controls in the admin console, plus an admin endpoint to decide any user's
  pending approval; previously approvals were read-only JSON and only the run owner could resume.
- `AdminAction` rows are now written for privileged mutations.
- Backend unit tests grew from 35 to 95; Flutter tests from 1 to 8; admin tests now exercise the
  approvals panel rather than asserting that `crypto.randomUUID` exists.

## 2.1.0 — 2026-08-20
- Added true pause/resume semantics for command approvals; the run resumes from the saved implementer command phase after the final decision.
- Added automatic worker re-queue after all approvals are decided and duplicate-start protection.
- Added immediate cancellation for queued/waiting runs so a project cannot remain locked by an abandoned run.
- Added parallel tester + security + dependency-intelligence verification.
- Added monorepo-aware Python, Node, Flutter and Gradle build profiles with validated per-command working directories.
- Required unavailable toolchains now block verification instead of being counted as successful skips. (Revised in 2.1.1: blocking is opt-in via `ENGINEERING_STRICT_TOOLCHAINS`, because the stock runner image ships no Flutter or Android toolchain.)
- Added local sparse code embeddings, symbol extraction, relevance-ranked code search, lazy invalidation and release-time index rebuild.
- Added authenticated code-index rebuild/search, Git status and per-run diff APIs.
- Added Android code search, run diff viewer, new-run flow, automatic approval-resume messaging and run cancellation controls.
- Hardened archive import against `.git`/`.ai-platform` metadata and disabled repository Git hooks.
- Added offline dependency manifest/lockfile/source analysis and high-severity dependency-source gating.
- Expanded engineering runtime unit coverage.

## 2.0.0
- Added autonomous engineering projects/runs/tasks/events.
- Added isolated workspaces and secure ZIP source import.
- Added multi-role task graph, bounded repair engine, quality gates and security scanner.
- Added Git checkpoints, project memory and human approval records.
- Added Android project/run monitoring, approval decisions, project export/rollback, and admin engineering overview.
- Added migration 0002 and runtime tests.


## 1.0.0 — 2026-08-19

- Initial production architecture and release packaging.
- FastAPI API v1, PostgreSQL async models/migrations, Redis rate limits/cache, Dramatiq workers.
- Argon2id authentication, rotating opaque refresh tokens, device binding/revocation, audit and security events.
- DeepSeek gateway with retries, timeout/error mapping, allowed-model controls, circuit breaker, cache, quotas and metadata-only prompt logging by default.
- React/TypeScript Material UI admin console.
- Flutter Android client with Hebrew RTL/English, secure token storage, automatic refresh, version gate, devices, notifications and AI chat.
- Nginx TLS-ready deployment, Prometheus/Grafana, backups, CI/security workflows and installation/upgrade verification scripts.
