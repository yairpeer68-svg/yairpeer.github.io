# Magen Phone v4.5.1 — Audited HTTPS Inspection

## v4.5.1
- Deep line-by-line reliability/security audit over VPN, TCP/UDP, HTTPS inspection, CA lifecycle, update pipeline and recovery.
- Fixed TCP handshake/SYN retransmission, sequence wrap, FIN/half-close, zero-window/ACK and packet-length edge cases.
- Connected UDP channels to the exact peer and hardened DNS/SNI/TCP parsers; deterministic 200k malformed-input fuzz pass.
- Removed explicit localhost proxy bypass; transparent MITM listener now requires a per-process secret preamble.
- Device-bound HTTPS inspection Root CA per enrolled device; sensitive-host exclusions remain fail-closed.
- KillSwitch is now a proper specialUse foreground service for background protection events.
- Signed updates now download privately, require signed SHA-256, package identity and release-signer verification before FileProvider install.
- Update trust key no longer defaults to the online VPS signing key.

## v4.5.0
- Dedicated managed HTTPS-inspection CA with VPS-isolated signer.
- Local explicit proxy on 127.0.0.1:18082 and transparent Full-Tunnel TLS interception on 127.0.0.1:18083.
- Per-host ephemeral EC P-256 leaf keys; short-lived exact-SAN certificates only.
- Device Owner CA lifecycle + recommended global proxy; manual Android CA workflow retained.
- No pinning bypass; hashed compatibility fallback for incompatible apps.
- Sensitive identity/payment/banking/health/password-manager hosts are never decrypted.
- TLS 1.2/1.3 only, upstream hostname validation, request-smuggling/header hardening, Host/SNI consistency checks.
- CA rotation detection, leaf-cache invalidation and bounded fallback cache.
- TcpRelay transparent redirect with selector-thread replacement and single-writer upstream ordering.

---

# Magen Phone v4.4.0 — Production Observability & Reliability

## v4.4.0

- Server endpoint validation is now strictly HTTPS + explicit port 8443, with no path/query/user-info.
- Android VPS client disables redirects, bounds responses, retries only safe operational calls and uses fresh signed nonces.
- Connectivity `NetworkCallback` triggers fast heartbeat recovery after Wi-Fi/mobile transitions with debounce/backoff.
- Heartbeat scheduling is single-flight: network pokes can move one pending task earlier but cannot create parallel recurring chains.
- All VPN revival paths (watchdog, tamper detector, UI and self-restart) are centralized through Android O+ safe `startForegroundService()`.
- Events and Content Incidents carry client-generated IDs for idempotent retries.
- Heartbeat now reports process instance, VPS failure streak, VPN restart count, Full Tunnel, Device Owner and real blocklist metadata.
- Fixed the previous heartbeat bug that always reported blocklist version 0.
- Production phone blocklist updates are signed-VPS-only; last-known-good cache is retained when the VPS is unavailable.
- Invalid server URLs are rejected in the UI without crashing the settings dialog.
- Static verifier now asserts v4.4 reliability/security invariants and its warning-print loop was fixed.
- Server adds worker liveness, signed runtime status, `/ready` runtime checks, `magenctl doctor`, `magenctl report`, and richer live monitoring.
- VPS blocklist build adds critical-domain poisoning and implausible-growth guards before signing.
- Fresh-install resume and upgrade rollback now cover more partial-failure states/configuration.
- VPS request-auth headers are shape/size validated before DB and ECDSA work, reducing malformed-request failure modes.
- `/ready` now validates that the application signing key is a real EC P-256 private key, not merely a readable file.
- Production configuration bounds timeouts, fetch sizes, redirects, retention knobs and PUBLIC_HOST format and fails fast at API startup.
- TEXT review privacy was tightened: AI-generated reasons are never persisted because a model could quote visible user text.
- Server remains `https://51.20.205.229:8443`; port 443 remains untouched.

## v4.3.0

- Unified Content Incident pipeline for DOMAIN / TEXT / VISUAL blocks.
- Privacy-minimized reporting: no screenshot bytes and no raw visible text are stored in incidents.
- Domain incidents carry normalized host + decision metadata; text incidents carry only SHA-256 + package; visual incidents carry package + numeric LiteRT scores.
- Reliable bounded incident queue with retry and client/server deduplication.
- Intelligence runtime counters: domain/text calls, cache hits, blocks and failures.
- Heartbeat now reports intelligence health and incident queue depth to the VPS.
- Safe VPN self-heal is requested from the heartbeat path when the already-authorized Magen VPN is down.
- Persistent server-verdict cache is now bounded to 2,500 live entries and periodically prunes expired/old entries.
- Telegram visible-text classification now records package context without persisting the visible text.
- Existing on-device Visual Shield remains local-only; image bytes never leave the phone.
- Production server endpoint remains `https://51.20.205.229:8443`.

## v4.2.4 hardening retained

- Signed Release APK build and `apksigner` verification.
- Persistent release signing identity + IntegrityGuard certificate pin.
- Full Tunnel production mode; no automatic DNS-only downgrade.
- TCP receive-window / zero-window / Window Scale fixes.
- Larger TLS ClientHello inspection budget.
- IPv6 remains fail-closed until a complete IPv6 relay is implemented.
- Crash telemetry redaction, Device Owner provisioning helper and fresh-server pairing import.