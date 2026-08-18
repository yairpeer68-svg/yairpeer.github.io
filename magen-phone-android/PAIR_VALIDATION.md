# Magen Phone + VPS — Pair Validation

This phone artifact keeps the same VPS URL, private-CA public certificate and server ECDSA verification key as the previously paired package. The onboarding/permission fixes do not alter the wire protocol or cryptographic pairing.

## Live VPS status confirmed in this deployment

- `magen-api`: active.
- `magen-worker`: active.
- `nginx`: active on public HTTPS :443.
- `postgresql`: active on loopback only.
- HTTPS health endpoint: OK.
- Central adult blocklist built successfully: 4,748,063 unique domains.
- Blocklist metadata contains SHA-256 + ECDSA P-256 signature.
- DeepSeek API connectivity test: OK.

## Phone validation after onboarding fix

- `verify.py --strict`: PASS (104 Java, 61 XML, 29 manifest components).
- Known permission-engine regressions are now explicit verifier failures.
- Device Admin activation is launched from the Activity with `startActivityForResult` and never as `NEW_TASK`.
- DeviceAdminReceiver no longer attempts to pop the activation UI from background.
- `SafeLaunch` adds `NEW_TASK` only for non-Activity contexts.
- VPN consent dialogs are not blocked during setup/maintenance grace.
- Runtime permission callbacks refresh the onboarding state immediately.
- Accessibility enabled-state checks use exact Magen service component matching across onboarding, tamper monitoring, heartbeat and diagnostics.
- Restricted Settings help is limited to the Accessibility step.
- Hebrew step counter has explicit LTR isolation.
- Unused `watch-login` Device Admin policy removed.
- No VPS URL, CA or response-signing key was changed by this patch.

## Remaining runtime validation

A full Android Gradle build cannot run in this artifact environment because Gradle distribution DNS is unavailable. The final proof still requires building/installing this package on the target phone and walking the onboarding flow once. The static verifier intentionally does not claim zero runtime bugs.
