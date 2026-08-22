# Android Client

The Android client is Flutter/Material 3 with Hebrew RTL and English. Screens include splash/loading, login, registration, home, AI chat, request history, devices, profile, settings, about, security and notifications.

## Configuration

Do not hardcode production URLs. Build with:

```bash
flutter build apk --debug --dart-define=API_BASE_URL=https://api.example.com/api/v1
```

Production Android manifest disables cleartext traffic. A debug-only manifest enables cleartext for emulator development so the default `http://10.0.2.2:8080/api/v1` works without weakening release builds.

## Token storage

`flutter_secure_storage` stores access/refresh tokens and the installation/server device identifiers. Tokens are never written to SharedPreferences. Access tokens are refreshed through a single in-flight refresh operation; a failed refresh clears credentials and returns the UI to authentication. A request is retried for authentication at most once. Only idempotent GET network failures receive one automatic transient retry.

## Device binding

The first authenticated launch registers the installation. The backend returns a database UUID that is stored securely and supplied on later logins. A revoked known device is rejected at login. Server-side registration also binds the active session and refresh token to the device.

## Certificate pinning

Optional pinning is controlled at build time:

```bash
--dart-define=CERT_PINNING_ENABLED=true \
--dart-define=CERT_PIN_SHA256_CURRENT=<lowercase sha256 of DER certificate> \
--dart-define=CERT_PIN_SHA256_NEXT=<next certificate hash during rotation>
```

The client accepts either configured hash, enabling overlap during certificate rotation. Add the next pin before replacing the certificate; after the installed app population has migrated, remove the old pin in a later release. Do not enable pinning with empty values. Pinning supplements normal TLS validation; it must not be used to accept an otherwise invalid certificate.

## Release signing

Copy `android/key.properties.example` to `android/key.properties` locally and point it at a separately protected upload keystore. `key.properties` and keystores must not be committed or packaged. If release signing data is absent, do not distribute the generated release artifact.

## Play Integrity

The client payload includes an optional attestation slot and the backend defines a verifier interface. This release does not pretend successful attestation: without a configured/implemented Google verification adapter the server reports `not configured` and devices remain untrusted.

## Engineering controls in 2.1

The Projects area now supports creating additional runs for an existing project, local relevance-ranked code search, live parallel-verification state, command approval/rejection with automatic server-side resume, cancellation, and a read-only Git diff viewer for completed runs. Source search and diff endpoints remain authenticated and scoped to the owning user/project.
