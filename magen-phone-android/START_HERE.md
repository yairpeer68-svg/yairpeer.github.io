# Magen Phone v4.2.1 — התחלה מהירה

גרסה זו היא `4.2.1-paired-8443`, ללא Device Owner, ומתאימה ל-Magen VPS v4.2.

## בניית APK ב-Windows
1. חלץ את ה-ZIP.
2. לחץ פעמיים על `BUILD_APK_ON_WINDOWS.bat`.
3. הסקריפט בוחר אוטומטית JDK 21/17 (ולא Java 25), מאתר Android SDK, מתקין API 36/Build Tools 36 אם חסרים, מוריד ומאמת את מודל ה-Visual AI, מריץ verifier + unit tests + assembleDebug ומאמת את חתימת ה-APK.
4. הפלט: `magen-v4.2.1-paired-8443-debug.apk` + קובץ SHA-256.

## סדר התקנה מומלץ
1. שדרג קודם את ה-VPS עם `UPGRADE_V4_2_FULL_HARDENING.sh`.
2. בשרת: `sudo magenctl visual strict`.
3. בנה והתקן את APK v4.2 בטלפון.
4. ודא ש-VPN, Accessibility ו-Device Admin פעילים.
5. בדוק `sudo magenctl health`, `sudo magenctl visual metrics` ו-`sudo magenctl events --limit 100` בשרת.

## Visual Shield
ההחלטה החזותית מבוססת על מה שמוצג במסך: סיווג מקומי של מסך מלא + tiles, temporal consensus ו-curtain אטום אחרי זיהוי. תמונות מסך אינן נשמרות ואינן נשלחות ל-VPS.

## מגבלות חשובות
ללא Device Owner אין לאפליקציה שליטה מוחלטת על Android. חלונות עם הגנת צילום מסך יכולים למנוע capture, וה-classification מתרחש אחרי שהפיקסלים נרנדרו. `verify.py` הוא verifier סטטי ואינו תחליף לבדיקת APK על מכשיר פיזי.

ראה `V4_2_FULL_HARDENING.md` לפרטים.
