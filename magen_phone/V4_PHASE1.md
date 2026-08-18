# Magen v4 — Phase 1 (no Device Owner)

This build starts the v4 phone/server control architecture without Device Owner.

- Encouragement messages now keep their server-defined context: `GENERAL`, `BLOCKED`, `PANIC`, `DAILY`, `MILESTONE`.
- `BlockedActivity` prefers `BLOCKED` messages.
- `MagenKillSwitch` prefers `PANIC` messages.
- Signed v4 `/v1/encouragement` responses are cached locally; v3 `sentences` remains a rolling-upgrade fallback.
- No Telegram bot/token/chat-id path is introduced.
- Version: `4.0.0-phase1`, versionCode `9`.

The server remains the source of truth for message content. The APK contains only local fallback text for offline operation.
