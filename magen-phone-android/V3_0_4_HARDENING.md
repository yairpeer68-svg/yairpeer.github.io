# Magen Phone 3.0.4 — hardening

- App-category selector redesigned as a normal management card.
- PIN verification never opens a global maintenance window. Permission flows receive a short, single-purpose scope only.
- Permission/App-info/VPN/Accessibility/Admin settings are blocked while protection is armed unless that exact screen has a short authenticated scope.
- Dynamic VPN detection inspects declared Android VpnService components, not only package names.
- Device Owner mode enforces Magen as always-on VPN with lockdown and adds OS-level VPN/app/debug/safe-boot restrictions.
- Legacy Telegram bot/SMS/accountability-phone integration removed. Security events go to the signed Magen VPS API.
- Encouragement sentences are synchronized from the signed VPS endpoint.
- Visible Telegram text/accessibility descriptions are classified asynchronously by the VPS/DeepSeek path even in LIGHT mode; known explicit terms are blocked locally without waiting for the network, and stale queued AI requests are dropped.
- This build does not capture or classify raw Telegram image/video pixels; visual-only media therefore cannot be guaranteed by the text/accessibility path.
- Central blocklist metadata path aligned with `/v1/blocklist/file`; public fallback feeds updated to current UT1/StevenBlack endpoints.

- ProtectionWatch now suppresses only the permission currently being changed instead of muting every protection check.
- Allowed Android settings screens are excluded from normal content scanning so settings text cannot trigger unrelated content blocks.
- Installed VPN apps are re-audited periodically and are suspended/hidden when Magen is Device Owner/Profile Owner.
