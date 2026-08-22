# Security Policy

## Supported release

Version 1.0.x is the initial supported line. Security fixes should be applied to the latest point release.

## Secrets

Secrets are environment-provided and excluded from release archives. Never commit `.env`, DeepSeek keys, SMTP passwords, JWT secrets, Android keystores, `key.properties`, Play Integrity service-account data, FCM credentials, or payment-provider credentials. The Android and admin clients never receive the DeepSeek API key.

## Authentication

Passwords use Argon2id. Access tokens are short-lived JWTs. Refresh tokens are opaque cryptographically random values; only SHA-256 hashes are stored in PostgreSQL. Refreshes rotate the token and track token families so detected reuse revokes the entire family. Sessions can be revoked globally or by device. Known revoked devices are rejected during login.

## Browser admin

The admin console uses Authorization bearer tokens stored only in process memory. It does not persist tokens in localStorage, sessionStorage, IndexedDB or cookies. Reloading the page therefore requires signing in again. If cookie authentication is added later, implement SameSite/HttpOnly/Secure cookies and explicit CSRF protection before enabling it.

## Reporting vulnerabilities

Do not include production secrets, raw user prompts, access tokens or refresh tokens in a report. Include the release version, affected endpoint/component, reproducible steps using non-sensitive test data, and impact assessment. Rotate any credential accidentally disclosed during investigation.

## Deployment boundary

Only Nginx should be Internet-facing. PostgreSQL and Redis are internal-only. The FastAPI container is exposed only to the edge Docker network. Use HTTPS in production and configure exact `TRUSTED_HOSTS`/`CORS_ORIGINS` values.

## Residual integration risks

Play Integrity and FCM are deliberately configuration-gated interfaces and never pretend to verify/send when an adapter is unavailable. Payment `MockPaymentProvider` creates only `pending` local-development intents and never marks a production payment paid.
