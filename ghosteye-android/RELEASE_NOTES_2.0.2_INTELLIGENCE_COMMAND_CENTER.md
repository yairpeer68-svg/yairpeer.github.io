# Ghost Eye Phone 2.0.2 — Intelligence Command Center

This release upgrades the phone workspace without weakening the server-side safety model.

## Added
- Interactive Entity Graph visualization with bounded node/edge rendering and tap-to-inspect navigation.
- Graph analytics summary in the entity workspace.
- Investigation Copilot plan directly from saved Intelligence Fabric investigations.
- Background Watchtower polling with Android WorkManager (15 minute minimum interval, network constrained).
- Local Android notifications for previously unseen Watchtower alerts.
- Foreground Watchtower refresh every 30 seconds while the screen is visible.
- Android 13+ notification permission handling.
- Version 2.0.2 / versionCode 42.

## Safety and reliability
- Background alert failures are isolated and retry through WorkManager.
- Expired sessions do not create an aggressive retry loop.
- Graph rendering is capped at 40 visual nodes and 120 edges even if the server returns more data.
- No API keys or provider secrets are stored in this source package.
- Copilot does not receive shell access; network policy remains server enforced.
