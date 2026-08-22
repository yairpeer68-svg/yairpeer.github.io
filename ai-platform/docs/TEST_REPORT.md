# Test Report — 2.1.1

Date: 2026-08-21. Every result below was produced by executing the named command in this build environment. Nothing that was not run is represented as a pass.

## Backend

| Check | Command | Result |
|---|---|---|
| Lint | `ruff check app tests` | **PASS** — 0 findings |
| Types | `mypy app` | **PASS** — 0 issues across 84 source files |
| Unit tests | `pytest -q tests/unit` | **PASS** — 95 passed |
| Runner lint | `ruff check runner.py` | **PASS** |
| Integration | `docker compose -f docker-compose.test.yml …` | **NOT RUN** — Docker unavailable here; remains a CI gate |

The 2.1.0 report listed Ruff as "executable unavailable — NOT RUN". When it was finally executed it produced **451 findings** and `mypy app` produced **24 errors**, so both gates had been failing since they were added. The correctness findings are fixed; `E701`/`E702` (compressed one-statement-per-line style, 431 of the 451) are now explicitly excluded in `pyproject.toml` rather than left as a permanently red check.

Unit coverage grew from 35 to 95 tests. New suites: archive extraction limits and the decompression-bomb path, execution-policy classification, quality-gate toolchain handling, code-index staleness, configuration validation, feature-flag evaluation, and provider retry idempotency.

## Admin console

| Check | Command | Result |
|---|---|---|
| Install | `npm ci` | **PASS** — `package-lock.json` is now committed |
| Lint | `npm run lint` | **PASS** |
| Tests | `npm run test` | **PASS** — 3 tests, jsdom environment |
| Build | `npm run build` | **PASS** — 921 modules, assets emitted under `/admin/assets/` |

`npm run build` had never been executed successfully: it failed on two TypeScript errors (`import.meta.env` untyped, and `allowImportingTsExtensions` without `noEmit`). Both are fixed. The build also confirms the `/admin/` base path fix — `dist/index.html` now references `/admin/assets/index-*.js`.

## Android client

| Check | Command | Result |
|---|---|---|
| Resolve | `flutter pub get` | **PASS** — `pubspec.lock` is now committed |
| Format | `dart format --set-exit-if-changed lib test` | **PASS** |
| Analyze | `flutter analyze` | **PASS** — "No issues found" |
| Tests | `flutter test` | **PASS** — 8 tests |
| APK build | `flutter build apk` | **NOT RUN** — no Android SDK in this environment; remains a CI gate |

Verified with Flutter 3.27.1 / Dart 3.6.0. Three gates were previously impossible to pass and had never been executed:

- `flutter pub get` failed outright — `intl: ^0.20.2` conflicts with the version `flutter_localizations` pins on this channel. `intl` was declared but never imported, so it was removed.
- `dart format --set-exit-if-changed` failed on 22 of 25 files, because the source used compressed one-liners. The tree is now formatted.
- `flutter analyze` exited 1 with 18 `curly_braces_in_flow_control_structures` findings. All are fixed.

## Interpretation

Environmental skips are not counted as passes. Docker integration tests and the Android APK/AAB build remain CI and deployment-host responsibilities. Container image builds, dependency scanners (Bandit, pip-audit, Semgrep, Trivy, npm audit, Gitleaks) and the signed release build were not executed here.
