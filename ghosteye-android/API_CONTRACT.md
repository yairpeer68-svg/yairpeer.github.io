# API Contract
POST /api/v1/files -> job_id
GET /api/v1/jobs/{id} -> status/progress
WS /api/v1/ws/jobs/{id} -> live progress
GET /api/v1/jobs/{id}/result -> evidence/findings/AI/SBOM
GET /api/v1/jobs/{id}/sbom -> SBOM
GET /api/v1/graph -> knowledge graph
POST /api/v1/diff -> artifact comparison

The Android client is a thin client; analysis happens on the server.

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
