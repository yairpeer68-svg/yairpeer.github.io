# Ghost Eye Phone 1.1.0 — OSINT + AI Expansion

- Provider Vault picker now includes OpenRouter, Anthropic, Gemini, Groq, Mistral, xAI, Netlas, BinaryEdge, LeakIX and FullHunt.
- AI providers receive reasoning permission; intelligence providers remain passive by default.
- Added Origin Server Exposure UI backed by the server passive-correlation endpoint.
- UI displays candidate count, top candidate confidence, source count and provider errors.
- Candidate origin IPs are not directly contacted by the phone feature.

Validation: Android release_guard clean. Full Gradle/Kotlin compilation was not verified because Gradle 8.7 is not cached in the offline environment.
