# API

All application endpoints are versioned under `/api/v1`. Errors use:

```json
{"error":{"code":"SOME_CODE","message":"Human-readable message","request_id":"..."}}
```

Clients may supply `X-Request-ID` using 1–64 safe characters. Invalid/missing IDs are replaced with a UUID. Every normal application response returns the selected request ID.

## Authentication

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/revoke-all`
- `POST /api/v1/auth/change-password`
- `POST /api/v1/auth/forgot-password`
- `POST /api/v1/auth/reset-password`
- `POST /api/v1/auth/verify-email`
- `GET /api/v1/users/me`
- `DELETE /api/v1/users/me`

Use `Authorization: Bearer <access-token>` for protected routes. Refresh tokens belong in request bodies only for refresh/logout and must never be put in URLs.

## Devices

- `POST /api/v1/devices/register`
- `GET /api/v1/devices`
- `DELETE /api/v1/devices/{uuid}`
- `POST /api/v1/devices/{uuid}/revoke`

The registration response's database UUID is the value Android stores as its server device ID. Registering a device binds the current session and active refresh token to that device.

## AI

- `POST /api/v1/ai/chat`
- `GET /api/v1/ai/history?limit=50`

The chat payload contains a bounded list of `system|user|assistant` messages, `temperature`, `max_tokens`, optional allowed `model`, and `cache`. The server applies a global prompt character maximum, output token maximum, model allowlist, Redis cache and per-user quotas.

## Notifications / flags

- `GET /api/v1/notifications`
- `POST /api/v1/notifications/{uuid}/read`
- `GET /api/v1/feature-flags`

## System

- `GET /health`, `/health/live`, `/health/ready`
- `GET /version`
- `GET /metrics`
- `GET /api/v1/system/app-version?platform=android`
- `GET /api/v1/system/integrations`

Readiness checks PostgreSQL and Redis and reports DeepSeek configuration separately without making a paid provider call.

## Admin

Admin authorization is enforced server-side. Main routes include user/device lists and updates, quota administration, AI usage, audit/security events, feature flags and per-user overrides, maintenance mode, app versions, subscriptions, payments, notifications and system status. List endpoints bound `page_size` and use whitelisted sort/filter parameters rather than interpolating SQL identifiers from clients.

## Autonomous engineering v2.1

Authenticated user routes are under `/api/v1/engineering`:

- `POST /projects`, `GET /projects`, `GET /projects/{project_id}`
- `POST /projects/{project_id}/archive` — safe ZIP import; reserved `.git` and `.ai-platform` metadata is rejected.
- `GET /projects/{project_id}/tree`, `GET /projects/{project_id}/file`
- `POST /projects/{project_id}/runs`, `GET /projects/{project_id}/runs`
- `POST /runs/{run_id}/start`, `POST /runs/{run_id}/cancel`
- `GET /runs/{run_id}`, `/tasks`, `/events`, `/approvals`
- `POST /approvals/{approval_id}/decision` — automatically resumes a waiting run after the final pending decision.
- `GET /projects/{project_id}/memory?q=...`
- `POST /projects/{project_id}/code-index/rebuild`
- `GET /projects/{project_id}/code-search?q=...&limit=...`
- `GET /projects/{project_id}/git-status`
- `GET /projects/{project_id}/checkpoints`
- `POST /projects/{project_id}/checkpoints/{checkpoint_id}/rollback`
- `GET /runs/{run_id}/diff`
- `GET /projects/{project_id}/export`

A project may have only one queued/running/waiting-approval run through the API at a time. Source search uses the platform's local sparse index; it does not invoke DeepSeek or an external embedding API.
