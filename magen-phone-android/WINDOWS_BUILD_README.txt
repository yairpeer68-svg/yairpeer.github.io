Magen v4.5.1 — Windows Production Build + Audited HTTPS Inspection
=======================================

Run:
  BUILD_APK_ON_WINDOWS.bat

The script builds a signed RELEASE APK, not a debug APK.
It runs strict static verification, release unit tests, assembleRelease,
apksigner verification, and writes an APK SHA-256 file.

First production build:
- A persistent EC P-256 release signing key is created in .magen-private.
- BACK UP .magen-private SECURELY.
- Never publish/share that directory.

Fresh VPS pairing:
  IMPORT_SERVER_PAIRING_ON_WINDOWS.bat C:\path\to\pairing-folder

Server endpoint:
  https://51.20.205.229:8443
