# Secure build — Magen Phone 3.0.4

## Debug

`BUILD_APK_ON_WINDOWS.bat` בונה APK debug ומחשב SHA-256.

## Release

Release דורש keystore אמיתי ו-SHA-256 של תעודת החתימה. אין להמציא fingerprint.
הגדר את ערכי Gradle/Environment המתאימים ל-`magenKeystoreFile`, סיסמאות/alias ו-`releaseCertSha256`.

## Server trust

האפליקציה משתמשת ב-HTTPS עם CA פרטי מוטמע ובחתימת ECDSA נפרדת ל-policies/verdicts/blocklists. ה-DeepSeek API key נשאר רק ב-VPS.

אין הרשאת SMS ואין credentials של שירות התראות חיצוני בתוך APK.
