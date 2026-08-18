# Universal Intelligence Android Client v0.4

The app now:
1. opens Android Storage Access Framework;
2. sends the selected file as multipart/form-data;
3. receives a job ID;
4. polls status;
5. displays progress;
6. fetches the final result;
7. displays basic Findings / Entities / Evidence / AI summary.

Configure `ApiClient(..., baseUrl, token)` for the production server.
For production use HTTPS only and store credentials securely.
