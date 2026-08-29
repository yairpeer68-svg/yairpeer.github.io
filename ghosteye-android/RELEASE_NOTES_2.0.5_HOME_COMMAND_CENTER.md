# Ghost Eye Phone 2.0.5 — Home Command Center

## Product/UI changes

- New Home Command Center as the default landing surface.
- Five-destination navigation: Home / Investigate / Graph / Watchtower / More.
- New live overview for Entity Graph, relationships, alerts, OSINT readiness and configured providers.
- New quick actions for Investigation, Entity Graph, Watchtower and CVE/OSINT tools.
- Recent investigation cards with risk-oriented accents.
- Prioritized alert previews on Home.
- Consolidated system readiness card for Fabric, Watchtower, Provider Vault and server state.
- Vulnerability Intelligence and OSINT Source Center moved under a clean More hub to reduce navigation clutter.
- Existing deep functionality is preserved; this release reorganizes rather than removes it.

## Reliability hardening

- Dashboard partial failures degrade independently.
- Session expiration is explicitly re-thrown and cannot be hidden by partial-dashboard fallback handling.
- Automatic Home refresh is bounded to once per minute.
- Existing Watchtower polling/background constraints remain unchanged.
- No provider secrets are rendered in Home.

## Verification performed

- Android release guard: clean.
- Modified Kotlin source structural delimiter checks: pass.
- New API calls reference existing ApiClient methods.
- No `.env`, `local.properties`, release keystore, build cache or generated secret file is packaged.
- Full Gradle compile could not be completed in the build environment because Gradle 8.7 is not cached and `services.gradle.org` is not DNS-reachable. Do not treat this package as APK compile-verified until built in a network-capable Gradle environment.
