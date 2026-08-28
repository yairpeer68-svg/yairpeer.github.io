# Ghost Eye Phone 10.3.0 — Clean Intelligence UI

## Main experience
- Main navigation reduced to two primary actions only: File and Domain.
- History moved to a compact top-bar action.
- Settings moved to a compact top-bar menu.
- No server address is displayed in the UI.

## File analysis
- One clear `נתח הכל` action.
- Cleaner upload/progress state.
- Results focus on risk, summary, useful information, findings and discovered assets.
- Raw JSON, evidence IDs, pipeline dumps and integrity dumps are no longer shown in the main result view.

## Domain / website / IP scanning
- Module checklist removed from the normal UI.
- The app automatically loads every available target module and sends all of them to the server.
- One clear `סרוק הכל` action scans all available information for the authorized target.
- Supports domain, URL and public IP targets.
- Authorization confirmation remains mandatory before active scanning.

## Results
- Duplicate/noisy entity values are deduplicated.
- Long hashes, UUIDs and internal IDs are filtered from the main result view.
- Findings are ordered by risk.
- Useful technical values are translated into readable labels.
- Large result sets are collapsed by default and can be expanded.

## Version
- versionName: 10.3.0
- versionCode: 20
