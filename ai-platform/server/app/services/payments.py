import abc
import secrets
from dataclasses import dataclass


@dataclass(frozen=True)
class PaymentIntent:
    reference: str
    status: str
    checkout_url: str | None = None


class PaymentProvider(abc.ABC):
    @abc.abstractmethod
    async def create_payment(self, amount_minor: int, currency: str, metadata: dict) -> PaymentIntent:
        raise NotImplementedError

    @abc.abstractmethod
    def verify_webhook(self, payload: bytes, signature: str, timestamp: str) -> bool:
        raise NotImplementedError


class MockPaymentProvider(PaymentProvider):
    async def create_payment(self, amount_minor: int, currency: str, metadata: dict) -> PaymentIntent:
        # Local development only: creates a pending intent and never fabricates a settled charge.
        return PaymentIntent(reference=f"mock_{secrets.token_hex(12)}", status="pending")

    def verify_webhook(self, payload: bytes, signature: str, timestamp: str) -> bool:
        return False
