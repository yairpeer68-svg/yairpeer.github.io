# API Contract
POST /api/v1/files -> job_id
GET /api/v1/jobs/{id} -> status/progress
WS /api/v1/ws/jobs/{id} -> live progress
GET /api/v1/jobs/{id}/result -> evidence/findings/AI/SBOM
GET /api/v1/jobs/{id}/sbom -> SBOM
GET /api/v1/graph -> knowledge graph
POST /api/v1/diff -> artifact comparison

The Android client is a thin client; analysis happens on the server.
