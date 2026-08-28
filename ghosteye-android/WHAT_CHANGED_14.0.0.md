# Ghost Eye Phone 14.0.0 — Unified Intelligence & Analysis

- Added a fourth main tab: Unified Intelligence center.
- Displays the server's full 80-feature capability registry and distinguishes configured, available and unavailable adapters.
- Added provider SLA/health, connector status, sandbox v3 isolation, infrastructure cluster count, playbooks and E2E certification status.
- Added Verified Investigation Review with bounded Copilot suggestions, counter-hypothesis challenge and deterministic multi-role evidence review.
- No external intelligence API key is stored or returned to Android.
- Existing Global Intelligence Graph search/pivot/timeline and investigation workflows remain intact.
- `versionCode 30`, `versionName 14.0.0`.

## Final verification improvements
- Provider SLA cards now consume the persisted historical latency/success-rate contract returned by server v14.
- Global-entity watchlist feedback is cleared when pivoting to another entity.
- Android CI builds both debug and release variants, then runs unit tests and lint.
- Gradle offline mode remains fail-fast: it never attempts a network bootstrap when 8.7 is absent from cache.
