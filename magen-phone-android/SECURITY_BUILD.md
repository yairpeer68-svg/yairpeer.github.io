# Secure build — Magen Phone v4.5.2 Audited HTTPS Inspection + Short-form Auto Skip

- Production output is `assembleRelease`; Debug output is never presented as final.
- Release signing identity is persistent/private under `.magen-private` unless CI explicitly supplies a keystore.
- `IntegrityGuard` receives the release certificate SHA-256 fingerprint at build time.
- The VPS uses a private CA embedded only as a public trust anchor; there is no trust-all TLS mode.
- Server URLs are restricted to HTTPS with explicit TCP 8443 and no path/query/embedded credentials.
- Redirects are disabled in the Android VPS client and response sizes are bounded.
- Device requests are signed; important server responses are independently application-signed.
- Operational retries use new nonces and client event/incident IDs for idempotency.
- Visual model trust is SHA-256 pinned; first bootstrap requires upstream digest or independent primary/mirror agreement.
- Full Tunnel is mandatory; automatic downgrade to DNS-only is disabled.
- IPv6 remains fail-closed until a complete IPv6 relay exists.
- Production Android accepts only the VPS-signed merged blocklist; direct unsigned public-list fallback is disabled.
- Device Owner remains optional but recommended for OS-level Always-On VPN/Lockdown enforcement.
