# Ghost Eye Phone 2.0.5 — Intelligence Command Center

Ghost Eye Phone is the Android command center for the Ghost Eye 2.0 Intelligence Fabric.

## Primary workspace

The 2.0.5 navigation is intentionally reduced to five clear destinations:

- **Home** — live command overview, Fabric/Graph metrics, Watchtower alerts, OSINT/API readiness, recent investigations and quick actions.
- **Investigate** — Intelligence Fabric investigations across OSINT, Evidence Fusion, Vulnerability Intelligence and persistent knowledge.
- **Graph** — Global Entity Graph, entity timelines, relationships and graph analytics.
- **Watchtower** — continuous watches, Risk Delta, prioritized alerts and acknowledge/evaluate controls.
- **More** — Vulnerability Intelligence and the OSINT Source/API Center without crowding the main navigation.

## Security and reliability

Provider secrets are sent only to the authenticated Ghost Eye server over HTTPS. Read APIs never return the secret value. Background Watchtower work is network-constrained and session expiration remains fail-closed.

The mobile client does not enable active exploitation or direct origin probing. Intelligence and OSINT workflows remain evidence-oriented and passive by default.

Version: **2.0.5** (`versionCode 45`).

See `RELEASE_NOTES_2.0.5_HOME_COMMAND_CENTER.md` and `API_CONTRACT.md`.
