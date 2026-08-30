# Ghost Eye Phone 2.1.0 — Website Security & Exposure

## Added / changed
- Target scan is presented as Website Security & Exposure.
- Unified exposure card shows exposure score, CVE totals, CISA KEV count and confidence distribution.
- CVE rows show technology/version, CVSS, EPSS, KEV/ransomware context and correlation reason.
- Sensitive exposed services include remediation guidance.
- DNS/HTTP/TLS posture is summarized directly in the result screen.
- Global screenshot blocking was removed.
- Screenshot/screen-recording protection is now applied only while a password/API secret is actually present in a credential field.

## Verification
- Android release guard: PASS
- Kotlin structural guard: PASS
- Gradle compile: NOT VERIFIED in this environment because Gradle 8.7 is not cached and bootstrap network access is unavailable.
