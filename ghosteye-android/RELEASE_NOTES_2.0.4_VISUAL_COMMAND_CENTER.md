# Ghost Eye Phone 2.0.4 — Visual Command Center

This release is a product/UI pass over the 2.0.3 Watchtower Intelligence client.

## Major UX changes
- New Ghost Eye design system with a denser cyber-intelligence visual hierarchy.
- Rebuilt top app bar with live connectivity status and cleaner history/investigation access.
- Rebuilt bottom navigation for Investigate, Entity Graph, Vulnerability, Watchtower and Sources.
- New command-center hero surfaces and consistent section hierarchy.
- Improved metric tiles, status pills, empty/error surfaces, spacing and typography.
- Reworked visual Entity Graph: selected node becomes the focus, connected edges are highlighted, entity types receive consistent accents.
- New dedicated OSINT Source Center instead of mixing provider configuration with unrelated cyber tools.
- Source Center supports provider search, configured-first ordering, health summary, secure key setup/replacement and enable/disable controls.
- Existing legacy/advanced cyber source tools remain accessible under Advanced Source Tools.
- Existing 2.0.3 Watchtower, Risk Delta, CVE/package watches, Copilot and background notifications are retained.

## Safety / privacy
- Provider secrets are still write-only from the phone; the server returns status/last4 only.
- No active exploitation or direct origin probing was added.
- FLAG_SECURE and encrypted session handling are retained.

## Verification
- Android release_guard clean.
- Kotlin patch delimiter checks clean.
- Kotlin parser-level smoke check found no syntax-token errors in changed files.
- Full Gradle compilation could not run in the build environment because Gradle 8.7 was not cached and DNS access to services.gradle.org was unavailable.
