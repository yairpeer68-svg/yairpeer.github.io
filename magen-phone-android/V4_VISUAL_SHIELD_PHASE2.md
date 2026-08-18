# Magen v4.1 Visual Shield — Phase 2

- Local screenshot classification through AccessibilityService (Android 11+/API 30+).
- Full-screen + 3x2 tiles to catch isolated visual content in feeds/search results.
- Strict policy blocks porn/hentai and, when configured, lingerie/sexy-class images.
- Opaque accessibility curtain hides content immediately after a positive local verdict.
- No screenshot bytes are stored, compressed, uploaded, or sent to the VPS.
- VPS receives only event metadata and signed visual thresholds/policy.
- Secure windows or unavailable screenshots fall back to existing text/URL/app protections.

The Windows builder downloads `nsfw_mobilenet_v2_140_224` from the GantMan/nsfw_model 1.1.0 release at build time.
The upstream release reports about 93% model accuracy; therefore Magen does not claim a universal 99% guarantee.
Tile scanning and strict thresholds are intended to increase practical recall for this use case.

## No Device Owner

This build intentionally does not ship Device Owner provisioning scripts. Visual Shield works through the existing Accessibility permission and local inference. Android can still be manually reconfigured by a sufficiently determined user; Magen's tamper/UI shields detect and react, but this is not equivalent to OS-managed Device Owner enforcement.
