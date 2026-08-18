# Magen Phone ↔ VPS Intelligence

הגרסה הזו מחליפה קריאות DeepSeek ישירות מהטלפון בארכיטקטורה פרטית:

`Android -> HTTPS private CA -> Magen VPS -> DeepSeek`

## אבטחה

- DeepSeek API key אינו קיים ב-APK ואינו נשמר ב-SharedPreferences.
- מפתח ECDSA P-256 לכל טלפון נוצר ב-Android Keystore ואינו ניתן לייצוא.
- כל API request אחרי enrollment נחתם על method/path/timestamp/nonce/body hash.
- השרת דוחה nonce כפול ו-timestamp ישן.
- policy ו-verdicts חתומים במפתח ECDSA נפרד של השרת בנוסף ל-TLS.
- HTTPS משתמש ב-CA פרטי שמצורף ל-phone package; אין `trust-all` ואין cleartext HTTP.

## Unknown domain flow

1. allowlist מקומי.
2. DoH/category/static/blocklist מקומיים.
3. cache של verdict מה-VPS.
4. אם אין verdict, נשלחת classification אסינכרונית.
5. `strict_unknown=true` חוסם זמנית עד שהשרת מחזיר verdict.
6. verdict חתום נשמר עם TTL מקומי; DeepSeek לא נקרא שוב בכל DNS request.

## Search/context flow

ה-class `DeepSeekClassifier` נשאר רק כ-facade תאימות לקוד Accessibility הקיים. הוא אינו מחזיק API key ואינו מדבר עם DeepSeek. הוא שולח snippet מוגבל ל-`/v1/intelligence/text` ב-VPS.

## חיבור

במסך מסנן התוכן יש:
- VPS URL
- Enrollment Code
- Enable Magen Intelligence
- Connect/Test VPS

ה-Enrollment Code נוצר בהתקנת השרת. אחרי enrollment הוא אינו נשמר בטלפון.

## Central blocklist sync

`RemoteBlocklist.update()` now prefers the paired VPS snapshot at `/v1/blocklist/meta`.
The metadata is verified with the embedded Magen server public key, the gzip is checked against the signed SHA-256 and hard size/count limits, and only then is a new Bloom cache published atomically. If this path fails, the existing UT1 + StevenBlack download path remains as an offline/server-failure fallback.
