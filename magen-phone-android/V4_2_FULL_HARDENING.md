# Magen Phone v4.2.1 — Full Hardening (No Device Owner)

## What changed

### Visual Shield 2
- Local-only screenshot classification via Android AccessibilityService on supported devices.
- Google LiteRT Interpreter runtime.
- Whole-screen classification plus up to six 3x2 tiles.
- Strict mode blocks porn, hentai and sexy/lingerie policy classes.
- Strong detections block immediately; borderline detections use short temporal consensus.
- Exact-only dHash deduplication in STRICT mode so small visual changes are not skipped merely because the overall screen looks similar.
- Latest-only scheduling: fast scrolling does not create an unbounded queue of stale screenshots.
- Power-save aware scan interval and failure backoff.
- Opaque accessibility curtain is shown before navigating away after a block.
- Screenshot pixels are never written to disk or sent to the VPS. Only decision metadata/events leave the phone.

### Visual model integrity
- Builder is pinned to GantMan/nsfw_model release 1.1.0.
- Builder queries GitHub release-asset metadata and verifies the archive SHA-256 when GitHub exposes a digest.
- The extracted TFLite model gets a persistent VISUAL_MODEL_SHA256.lock.
- The same model hash is compiled into BuildConfig and verified again on-device before the model is opened.
- Full upstream MIT notice is packaged inside the APK assets.

### Policy and rollback safety
- Signed policy versions are monotonic on the phone; older server payloads are rejected.
- Visual thresholds, intervals, temporal settings and failure limits are range-clamped.
- Server cannot enable screenshot upload: there is intentionally no image-upload implementation in the phone.

### Health telemetry
Heartbeat metadata now includes:
- VPN / Accessibility / Device Admin / overlay state
- app/build/sdk version
- policy and blocklist versions
- visual model readiness
- visual scan, block and duplicate-skip counters
- consecutive visual failures

No screenshot bytes are included in heartbeat/event telemetry.

### Existing hardening retained
- Scoped permission-maintenance windows instead of a global bypass window.
- Dynamic detection of apps declaring Android VpnService, excluding Magen itself.
- Magen VPN consent requires the authorized flow after setup.
- Device Admin activation fixed to launch from Activity without NEW_TASK.
- Server-side alerts/events; no Telegram bot token, chat id or accountability phone number.
- Visible Telegram text can use the VPS/DeepSeek classification path; visual content uses the local Visual Shield.
- Signed VPS policy/verdict/blocklist protocol and device ECDSA identity.
- Local blocklist/cache continues to protect during VPS outages.

## Important limits
Magen v4.2 intentionally does not use Device Owner. Android therefore does not give it absolute control over the device: a sufficiently determined device user can still reach OS recovery/uninstall paths depending on OEM/version. Accessibility screenshot capture can also be unavailable for secure windows. Visual classification happens after pixels are rendered, so this design minimizes exposure but cannot guarantee that no unsafe pixel is ever displayed. The upstream visual model itself is reported around 93% accuracy; Magen's tiled/temporal policy is designed to improve practical recall, but an independent screenshot benchmark is required before claiming a higher numeric accuracy.

## Windows build
Run `BUILD_APK_ON_WINDOWS.bat`. The builder selects JDK 21/17, prepares API 36, downloads/verifies/locks the visual model, runs `verify.py --strict`, runs Gradle unit tests, builds the debug APK, verifies its APK signature and writes a SHA-256 file.
