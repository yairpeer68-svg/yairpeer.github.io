# API Contract
POST /api/v1/files -> job_id
GET /api/v1/jobs/{id} -> status/progress
WS /api/v1/ws/jobs/{id} -> live progress
GET /api/v1/jobs/{id}/result -> evidence/findings/AI/SBOM
GET /api/v1/jobs/{id}/sbom -> SBOM
GET /api/v1/graph -> knowledge graph
POST /api/v1/diff -> artifact comparison

The Android client is a thin client; analysis happens on the server.

Operational probes:
- `GET /health/live` — unauthenticated liveness probe.
- `GET /health/ready` — unauthenticated dependency-aware readiness probe; returns `503` until database, Redis, storage and the isolated analyzer are ready.

## 10.7 Autonomous Investigations

Authenticated endpoints used by the Android client:

- `POST /api/v2/investigations` — create from `seed_job_id` or an explicitly authorised public `target`.
- `GET /api/v2/investigations` — list investigations.
- `GET /api/v2/investigations/{id}` — investigation detail, items and linked job summaries.
- `POST /api/v2/investigations/{id}/pause`
- `POST /api/v2/investigations/{id}/resume`
- `POST /api/v2/investigations/{id}/cancel`
- `GET /api/v2/investigations/compare/{old_id}/{new_id}`
- `POST /api/v2/investigations/{id}/reports/sign`
- `GET /api/v2/investigations/reports/{report_id}/verify`
- `DELETE /api/v2/investigations/{id}` — terminal investigations only.


## 11.0 Autonomous Intelligence endpoints

- `GET /api/v2/investigations/{investigation_id}/intelligence` — evidence-first correlated brief.
- `POST /api/v2/investigations/{investigation_id}/snapshots` — save a point-in-time brief snapshot.
- `GET /api/v2/investigations/{investigation_id}/snapshots` — list owner-scoped snapshots.
- `GET /api/v2/investigations/snapshots/compare/{old_snapshot_id}/{new_snapshot_id}` — compare risk/findings/entities.
- `DELETE /api/v2/investigations/snapshots/{snapshot_id}` — delete one snapshot.

Snapshots do not trigger network activity. New network scans remain subject to the existing explicit-authorisation and public-target validation rules.


## 12.0 Autonomous Intelligence Core

- `GET /api/v2/investigations/{investigation_id}/autonomy` — current policy mode, deterministic plan and evidence-chain summary.
- `PATCH /api/v2/investigations/{investigation_id}/autonomy` — switch `observe`, `assist` or `bounded` for a running owner-scoped investigation.
- `GET /api/v2/investigations/{investigation_id}/plan` — deterministic current task graph/plan.
- `GET /api/v2/investigations/{investigation_id}/correlations` — cross-job normalized evidence correlation.
- `GET /api/v2/investigations/{investigation_id}/evidence-chain` — tamper-evident investigation event ledger and integrity status.
- `GET /api/v2/investigations/{investigation_id}/explain` — explainable autonomy/guardrail summary.

Network actions remain fail-closed: bounded mode alone is not sufficient; owner authorization, remaining scan budget and server-side public-target validation are also required.

## 12.1 Intelligence Sources Federation

- `GET /api/v2/intelligence/sources`
- `GET /api/v2/investigations/{investigation_id}/sources`
- `POST /api/v2/investigations/{investigation_id}/sources/refresh`
  - JSON body: `{ "privacy_mode": "local_only|passive_external|submission_allowed", "max_sources": 1..12 }`
- `GET /api/v2/investigations/{investigation_id}/timeline`

The Android client never receives or stores provider API keys.
- `POST /api/v2/intelligence/lookup` — direct passive domain/IP/ASN source lookup without creating an investigation.

## Ghost Eye 13.0.0 — Global Intelligence Graph
- `GET /api/v3/intelligence/search?q=...&entity_type=...&limit=...` — owner-scoped canonical entity search.
- `GET /api/v3/intelligence/entities/resolve?entity_type=...&value=...` — resolve a canonical entity already known to the owner.
- `GET /api/v3/intelligence/entities/{entity_id}` — entity summary plus enriched relationships.
- `GET /api/v3/intelligence/entities/{entity_id}/timeline` — temporal provenance across the owner's investigations.
- `GET /api/v3/intelligence/graph?entity_id=...&depth=...&max_nodes=...` — bounded graph neighborhood.
- `POST /api/v3/intelligence/investigations/{id}/sync` — idempotent local graph materialization.
- `GET /api/v3/intelligence/investigations/{id}/knowledge` — investigation graph counters/sync summary.
- `GET/PATCH /api/v1/projects/cases/{case_id}` — owner-isolated case detail/update with status, priority and tags.

No provider API keys are sent to Android. Global-graph sync is local and does not submit artifacts to external providers.

## Ghost Eye 14.0.0 — Unified Intelligence & Analysis

Android consumes the authenticated owner-scoped `/api/v4` surface for the Unified Intelligence dashboard and verified investigation review. Important read endpoints include:

- `GET /api/v4/capabilities`
- `GET /api/v4/providers/sla`
- `GET /api/v4/connectors`
- `GET /api/v4/clusters`
- `GET /api/v4/sandbox/status`
- `GET /api/v4/playbooks`
- `GET /api/v4/certification/matrix`
- `GET /api/v4/investigations/{id}/copilot`
- `GET /api/v4/investigations/{id}/challenge`
- `GET /api/v4/investigations/{id}/multi-agent-review`

All requests continue to use Bearer access tokens and `X-Ghost-Eye-Client`. Provider credentials and connector secrets remain server-side and are never represented in Android models or UI state.


## Ghost Eye 15.0.0 — Cyber Operations

Authenticated owner-scoped v15 surfaces:

- `GET /api/v5/cyber/capabilities`
- `GET/PATCH /api/v5/cyber/control` — active-operations state, global Kill Switch, approval requirement and bounded request rate.
- `GET/POST/DELETE /api/v5/cyber/scopes...` — authorized domains/CIDRs plus Rules of Engagement.
- `POST /api/v5/cyber/approvals` and `POST /api/v5/cyber/approvals/{id}/decision` — explicit human approval workflow.
- `GET /api/v5/cyber/policy/check` — fail-closed policy evaluation before an active operation.
- `GET/POST /api/v5/cyber/assets` — owner-scoped cyber asset inventory.
- `POST /api/v5/cyber/assess/headers` and `POST /api/v5/cyber/vulnerabilities/prioritize` — non-destructive assessment/prioritization.
- `GET/POST /api/v5/cyber/incidents`, incident events/timeline, evidence chain verification and detection coverage.
- `GET /api/v5/providers` and `PUT/DELETE /api/v5/providers/{provider}` — encrypted server-side provider vault. Secret values are never returned.
- `GET /api/v5/providers/usage/summary` — provider request/cost telemetry.
- `GET /api/v5/cve`, `GET /api/v5/cve/{cve_id}`, `GET /api/v5/cve/sources`, `POST /api/v5/cve/sync`, and component-to-CVE matches.
- `POST /api/v5/ai/council` — evidence-grounded Ghost AI Council for an owner-scoped investigation.

Safety contract: active operations default to disabled; global Kill Switch defaults to ON; human approval defaults to required; destructive exploitation, credential attacks and unrestricted shell access are not exposed by this API.

## Free OSINT Mesh 1.1
- `GET /api/v2/intelligence/free-osint-registry` — authenticated registry of free/open/community OSINT sources and active adapter state. Never returns provider secrets.
- Origin exposure remains passive-only; CT/archive-discovered subdomains may be DNS-resolved, but candidate origin IPs are not contacted.
