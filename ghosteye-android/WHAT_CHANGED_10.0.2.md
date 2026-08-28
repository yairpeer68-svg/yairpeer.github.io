# Ghost Eye Phone 10.0.2

- Uses a single resilient `/api/v2/mobile/bootstrap` request for Dashboard, History and Projects.
- Falls back to the legacy v1 endpoints if the server has not been upgraded yet.
- A failure in one optional server component no longer blanks all mobile data.
- Keeps the 10.0.1 coroutine/session crash fixes.
- Designed for Ghost Eye Server 10.0.2, which fixes artifact type preservation and ZIP/generic analysis.
