from dataclasses import dataclass

from app.core.config import Settings


@dataclass(frozen=True)
class AttestationResult:
    status: str
    valid: bool
    details: dict


class PlayIntegrityVerifier:
    def __init__(self, settings: Settings):
        self.settings = settings

    async def verify(self, token: str) -> AttestationResult:
        if not self.settings.PLAY_INTEGRITY_PROJECT_NUMBER or not self.settings.PLAY_INTEGRITY_CREDENTIALS_JSON:
            return AttestationResult("not configured", False, {})
        if not token:
            return AttestationResult("invalid", False, {"reason": "missing token"})
        # Verification requires Google's service account OAuth exchange. The production adapter is intentionally
        # configuration-gated; it never claims success without calling Google's verification service.
        return AttestationResult("not configured", False, {"reason": "google verifier adapter not enabled in this build"})
