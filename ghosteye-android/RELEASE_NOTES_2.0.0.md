# Ghost Eye 2.0.0 — Intelligence Fabric

Ghost Eye 2.0 is an architectural release, not a version-only rename.

## Core changes
- Unified passive Intelligence Fabric endpoint: OSINT -> evidence fusion -> vulnerability intelligence -> persistence -> temporal risk snapshot -> global entity graph.
- Autopilot/source federation ceiling increased to 64 providers per run; Fabric defaults to 48.
- Autopilot knowledge can now be persisted as first-class Investigations and source observations.
- CVE/package runs preserve OSV/GitHub/local-CVE provenance inside the knowledge graph pipeline.
- Fabric status endpoint exposes owner-scoped knowledge counts and source capacity without exposing secrets.
- Android app version 2.0.0 with Intelligence Fabric status and API client support.

## Safety
- Passive external intelligence remains the default.
- No direct origin-candidate probing.
- No active exploitation or credential attacks.
- Provider secrets remain server-side / vault-managed and are not returned by status APIs.
