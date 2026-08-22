# Security Review — 2.0.0

Review date: 2026-08-19. Scope: backend, Android client source, admin source, Docker/Compose, Nginx, CI, backup/release scripts.

## Findings fixed before release

| Severity | Finding | Resolution |
|---|---|---|
| High | Refresh-token reuse revoked the token family but left its server session active until access-token expiry. | Reuse now revokes the complete refresh family **and** the bound session under the same transaction. |
| High | AI `X-Device-ID` accepted arbitrary UUIDs, allowing an invalid FK to fail accounting after a provider call. | Device ID is parsed and verified for ownership/revocation before any provider request. |
| High | Daily request-quota rejection could leave a reserved token amount in Redis. | Reservation release is performed for request/day/token rejections and DB quota-state failures. |
| High | Compose YAML used unquoted `${...}` interpolation inside flow mappings and did not parse as YAML. | Values were quoted and the three Compose files parse successfully with PyYAML. |
| Medium | AI accounting/cache/circuit failure paths could propagate infrastructure exceptions inconsistently. | Quota/rate-limit are fail-closed; cache/circuit state is best-effort; DB rollback and explicit error mapping were added. Provider token usage is not incorrectly refunded after a provider response has already incurred cost. |
| Medium | Worker was connected only to an internal Docker network, preventing SMTP/provider egress. | Added a dedicated un-published `egress` network for the worker while PostgreSQL/Redis remain internal-only. |
| Medium | A rejected non-admin Admin UI login discarded tokens without explicitly closing the server session. | Admin client now calls backend logout before discarding a non-admin session and on normal sign-out. |
| Medium | Client-supplied request IDs had originally been considered for uniqueness in AI metadata, creating a collision/DoS risk. | `request_id` is indexed but intentionally non-unique; incoming IDs are length/character validated and otherwise replaced with UUIDv4. |
| Low | DeepSeek retry jitter used the general PRNG. | Retry jitter now uses `secrets` so security tooling does not flag it and randomness is process-safe. |

## Controls verified in source

- Passwords: Argon2id; minimum 10 characters, weak-password rejection, bounded maximum length.
- Access tokens: short-lived JWT with issuer, expiry, session ID and random JTI; production algorithm allow-list.
- Refresh tokens: opaque CSPRNG values; only SHA-256 hashes stored; rotation, expiry, revocation, family reuse detection and device/session binding.
- Secrets: no production API key or password committed; DeepSeek key is server-only; `.env` is excluded from packaging.
- Authorization: admin APIs require server-side `is_admin`; device ownership and user ownership are checked server-side.
- Privacy: raw AI prompts are not stored by default. Optional prompt retention requires a Fernet key.
- Logging: structured JSON; common secret/token/password fields and bearer/provider-key text are redacted; Uvicorn access log is disabled in the container.
- Network: PostgreSQL and Redis have no host port publishing; Nginx is the public entrypoint; production metrics are not exposed through Nginx.
- Browser admin: bearer/refresh credentials live in memory only and are omitted from browser credential cookies.
- Nginx: TLS 1.2/1.3 configuration, HSTS, CSP for Admin, X-Frame-Options, nosniff, referrer and permissions policies.
- Containers: non-root application/Nginx users, read-only filesystems where practical, `no-new-privileges`, all Linux capabilities dropped.
- Webhook helper: HMAC constant-time comparison, timestamp tolerance and payload hash; DB event IDs are unique for idempotency architecture.
- Backups: compressed PostgreSQL custom-format backup, SHA-256 sidecar, restrictive permissions, configurable retention, destructive restore confirmation.

## Remaining risks / external integration boundaries

1. **Play Integrity — Medium:** architecture is present and never fabricates success, but the Google verification adapter is intentionally not implemented in this release. Server returns `not configured`/untrusted until a real adapter is added.
2. **FCM push — Medium:** durable in-app notifications are implemented and a push-provider boundary exists. The live FCM adapter is not included; a configured-but-unimplemented worker fails explicitly rather than claiming delivery.
3. **Payment providers — Medium:** the abstraction and a development-only pending `MockPaymentProvider` exist. Stripe/Tranzila/CardCom/PayPlus live adapters are not included and no production payment success is fabricated.
4. **Dependency scanners — Medium until CI executes:** Bandit, pip-audit, Semgrep, Trivy, npm audit and Gitleaks are wired in CI, but these binaries/dependency graphs could not all be executed in the isolated build environment.
5. **Certificate pinning — Operational:** optional Android pinning supports current/next SHA-256 certificate DER pins. It is disabled by default to avoid shipping unusable pins; operations must rotate pins before certificate changes.
6. **Server host hardening — Operational:** firewall, SSH source restriction, secret-manager use and unattended security patching remain deployment responsibilities described in `SECURITY_HARDENING.md`.

No unresolved critical-severity source finding was identified by the manual review. External dependency and full container/mobile build verification remains required before a public production launch.


## v2 addendum
Autonomous Engineering Runtime v2 adds isolated workspaces, an internal no-egress runner, project/run/task/event persistence, bounded repair, security scanning, Git checkpoints, approval records, export/rollback APIs, and Android/Admin controls. Backend unit suite: 27/27 passed in the release build environment.

## v2.1 engineering-runtime hardening

- Imported project archives now reject `.git` and `.ai-platform` metadata in addition to symlinks/path traversal. Runtime Git operations set `core.hooksPath=/dev/null` so repository-local hooks cannot execute.
- Approval decisions cannot override the command allowlist. Only already-allowlisted commands can enter the approval flow; blocked tools remain blocked.
- Approval runs pause and resume from persisted task state rather than repeating prior AI steps.
- Runner working directories are validated as descendants of the same workspace before execution.
- Project mutation endpoints use project/run/approval row locks where relevant to reduce duplicate starts and concurrent mutation races.
- Required unavailable build toolchains are fail-closed verification blockers rather than successful skips.
- Local code-search vectors are generated on-server from project content and are not sent to an external embedding provider.
- Dependency intelligence flags insecure direct package sources; fresh CVE data is not claimed without an explicitly configured vulnerability feed.


## v2.1.1 review — findings fixed

| Severity | Finding | Resolution |
|---|---|---|
| High | ZIP extraction budgeted against `ZipInfo.file_size`, a value the archive author controls. A member declaring a tiny size could decompress without limit and fill the volume. | The budget counts bytes actually written and aborts mid-stream. The whole central directory is validated before any write, and a rejected archive is rolled back. |
| High | The command allow-list treated `python`, `node`, `npx` and `git` as safe by executable name only, so `python -c`, `node -e`, `node -r` and `git -c core.gitProxy=…` passed policy. | Inline-code flags are refused per interpreter and Git configuration-override flags are refused, in both the API-side classifier and the runner. |
| High | No per-account ceiling on engineering resources: unlimited projects, unlimited cumulative workspace bytes (each import reset the file counter without clearing the tree), and no run timeout. | Added `ENGINEERING_MAX_PROJECTS_PER_USER`, `ENGINEERING_MAX_ACTIVE_RUNS_PER_USER`, `ENGINEERING_MAX_WORKSPACE_BYTES` and `ENGINEERING_RUN_TIMEOUT_SECONDS`. Imports replace the previous tree by default. |
| Medium | The runner compared its bearer token with `!=`, leaking the prefix through response timing. | `secrets.compare_digest`. |
| Medium | `--forwarded-allow-ips=*` let any peer reaching the API spoof `X-Forwarded-For`, defeating per-IP rate limiting and poisoning audit attribution. | Trust is pinned to the Nginx gateway via `FORWARDED_ALLOW_IPS`. |
| Medium | Deleting an account anonymised the row but left the user's imported source code on disk with no owner able to remove it. | `DELETE /users/me` purges every project workspace before anonymising. |
| Medium | HTTP metrics fell back to the raw request path when no route matched, so arbitrary 404 URLs created unbounded Prometheus series. | Unmatched requests are labelled `unmatched`. |
| Medium | A run was claimed with a plain read, so two workers could both observe `queued` and execute the same run against one workspace. | The run is claimed with `SELECT … FOR UPDATE SKIP LOCKED`. |
| Medium | `add_header` inside `location /admin/` discarded the server-level security headers for exactly the surface that needs them. | Every location repeats the baseline headers. |
| Medium | Retention windows (`AUDIT_RETENTION_DAYS`, `AI_METADATA_RETENTION_DAYS`) were configured but never applied: the cleanup actor existed and nothing enqueued it. | A `scheduler` service enqueues the sweep on a fixed interval. |
| Medium | Read/write timeouts to the AI provider were retried, so a request the provider had already accepted could be billed more than once. | Only connection-phase failures retry; a read timeout fails closed. |
| Medium | Prompt retention could be enabled in production without an encryption key, failing only at request time. | Rejected at configuration load. |
| Low | The daily token-quota key was recomputed from "today" on release, so a request spanning UTC midnight refunded a different day's counter. | The charged key is pinned for the lifetime of the reservation. |
| Low | `adjust()` refreshed the quota key TTL on every write, sliding the daily window forward indefinitely for an active account. | `EXPIRE … NX` preserves the original expiry. |
| Low | An agent response could request unbounded file operations and commands in a single step. | Capped per response. |
| Low | The API's `default-src 'none'` CSP also applied to `/docs`, leaving Swagger unusable outside production. | `/docs` and `/openapi.json` get a scoped policy. |

## Verification status

Unlike previous releases, the security-relevant checks were executed rather than described: `ruff` (0 findings), `mypy` (0 issues), and 95 backend unit tests including direct regression tests for the decompression bomb, path traversal, workspace quota and execution policy. `bandit`, `pip-audit`, `semgrep`, `trivy` and `gitleaks` remain CI responsibilities and were not run here.

## Residual risk

The allow-list is a policy control, not a sandbox boundary. Quality gates execute code from the imported repository by design — `conftest.py` via pytest, `scripts` via `npm run`, `./gradlew` from the archive — and no approval covers them, because approvals only gate commands the *AI* requests. The real boundary is the runner container: no egress (`internal` network), non-root, `cap_drop: ALL`, `no-new-privileges`, read-only root filesystem, pids/memory/CPU limits and per-process rlimits. Treat any weakening of that container as a change to the platform's trust boundary.
