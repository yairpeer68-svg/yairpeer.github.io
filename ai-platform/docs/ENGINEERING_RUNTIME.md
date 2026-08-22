# Autonomous Engineering Runtime v2.1

The runtime treats software development as an auditable state machine instead of a chat session.

## Lifecycle

`project -> source import -> pre-run checkpoint -> planner -> architect -> implementer -> optional approval pause -> parallel verification -> independent review -> bounded repair -> acceptance -> release checkpoint + code index + diff`

Every run, task, event, approval and checkpoint is persisted in PostgreSQL. Source files live in an isolated workspace rooted below `ENGINEERING_WORKSPACE_ROOT`. All paths are normalized and path traversal, archive symlinks, oversized archives and oversized generated files are rejected.

## Agent graph

The default graph contains these roles:

1. `planner` — acceptance criteria, constraints and implementation plan.
2. `architect` — repository-aware architecture review.
3. `implementer` — validated write/mkdir operations and optional command requests.
4. `tester` — monorepo-aware build/test gates.
5. `security` — secret and risky-pattern scanning.
6. `dependency` — manifest, lockfile and dependency-source analysis.
7. `reviewer` — independent review using verification evidence.
8. `repair` — bounded evidence-driven repair.
9. `qa` — final acceptance decision.
10. `release` — Git checkpoint, run diff and local code-index rebuild.

Tester, security and dependency intelligence run concurrently after implementation. The reviewer waits for all three outputs.

## Agent protocol

Implementation/repair agents return a strict JSON envelope. The server validates it before applying writes. Deletion is deliberately absent from the autonomous protocol. AI-requested commands are allow-listed and executed with argument arrays, never via a shell.

## Resumable human approval

When automatic execution is disabled, an allow-listed AI-requested command creates an approval record. The implementer task changes to `waiting_approval` and the run stops. Decisions are audited. Once the last pending approval is approved or rejected, the worker is automatically re-queued. The run loads its existing tasks and pre-run checkpoint, resolves only the saved command phase, then continues with verification; planner/architect/implementation work is not repeated.

Commands outside the allow-list or blocked by policy never become executable merely because a user approves them. They remain blocked.

## Isolated runner

Dynamic commands are sent to the internal `runner` service. The runner receives no PostgreSQL, Redis, DeepSeek, SMTP or payment credentials and is attached only to an internal no-egress runner network. Child processes receive a minimal environment. Local execution is disabled by default and forbidden by production validation.

Version 2.1 adds a validated per-command working directory so monorepos can run checks in `backend/`, `web/`, `android/` or other subprojects without escaping the workspace.

## Build profiles and quality gates

The runtime detects Python, Node, Flutter and Gradle targets up to a bounded repository depth. It generates gates per target and runs them in the correct project directory. Required missing toolchains are **blocking verification results**, not fabricated passes. Optional informational checks can still be marked skipped.

## Security and dependency intelligence

The static security pass scans text source/configuration for accidental secrets and selected risky execution patterns. High-severity findings block acceptance.

Dependency intelligence parses common Python, Node, Dart/Flutter and Gradle manifests. It reports floating/unpinned declarations, missing lockfiles and insecure direct sources. This analysis is intentionally offline; current CVE data requires a separately configured vulnerability database/feed and is not fabricated when unavailable.

## Local code index

`CodeIndex` builds deterministic sparse feature-hash vectors over source identifiers, paths and discovered symbols. The index is stored under reserved `.ai-platform/` workspace metadata and is excluded from project export and manifest hashing. Search combines vector similarity with path/symbol boosts. Source code is not sent to a third-party embedding service for this feature.

The index is invalidated automatically when the workspace manifest changes and is rebuilt lazily or at release.

## Build toolchains

`profiles.py` detects Python, Node, Flutter and Gradle targets and emits their gates as
required. The stock `runner/Dockerfile` installs **Python and Node only**, so `flutter`,
`dart` and `./gradlew` are absent and their gates report `toolchain_missing`.

`ENGINEERING_STRICT_TOOLCHAINS` decides what that means:

- `false` (default) — the run continues and the gate is recorded as unverified. `quality.verified`
  is `false` and `missing_toolchains` names the tools. Without this, every Flutter or Android
  project failed its run by construction.
- `true` — a missing toolchain fails the run.

To make those gates authoritative, build the opt-in image that carries the Flutter, Dart and
Android SDKs and set the flag:

```bash
RUNNER_DOCKERFILE=Dockerfile.flutter docker compose build runner
ENGINEERING_STRICT_TOOLCHAINS=true
```

A gate blocked by execution policy is never excused as a missing toolchain; it always fails.

## Resource ceilings

Per account: `ENGINEERING_MAX_PROJECTS_PER_USER`, `ENGINEERING_MAX_ACTIVE_RUNS_PER_USER`.
Per project: one active run, `ENGINEERING_MAX_WORKSPACE_BYTES` of storage,
`ENGINEERING_MAX_PROJECT_FILES` files. Per run: `ENGINEERING_RUN_TIMEOUT_SECONDS`
wall clock, after which the run is marked failed. Archive import replaces the previous tree,
so repeated imports cannot accumulate without bound.

## ZIP and Git hardening

Archive limits are enforced against **bytes actually written**, not the size declared in the
ZIP header — that value is chosen by whoever built the archive, so a member declaring a tiny
size could otherwise decompress without bound. The entire central directory is validated
before the first byte is written, and a rejected archive leaves no partial tree.

Nginx must also accept the archive: `ENGINEERING_MAX_ARCHIVE_MB` sets the gateway body limit
and must be at least `ENGINEERING_MAX_ARCHIVE_BYTES`, otherwise uploads are rejected with 413
before reaching the API. CI asserts the relationship.

Imported archives may not contain `.git` or `.ai-platform` paths and may not contain Unix symlinks. This prevents untrusted repository metadata from controlling the platform's Git behavior. Git hooks are explicitly disabled for runtime Git operations.

## Diff and recovery

A pre-run checkpoint is created once and reused across approval resumes. A release checkpoint is created after QA. `GET /engineering/runs/{run_id}/diff` returns the bounded Git diff and changed-file list from pre-run to release (or current `HEAD` while available). Rollback is blocked while a project has an active run.

## Concurrency safety

Only one queued/running/waiting-approval run is accepted per project through the API. This avoids concurrent agents mutating the same workspace. Verification sub-agents may run concurrently because they are read/test phases against the same accepted implementation state.
