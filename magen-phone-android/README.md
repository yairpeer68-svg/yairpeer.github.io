# Magen Phone v4.5.2 — Audited HTTPS Inspection + One-shot Short-form Auto Skip (8443)


## v4.5.2: HTTPS Inspection + Short-form Auto Skip

### One-shot short-form behavior

When the local Visual Shield confirms unsafe content on a supported vertical short-form feed, Magen hides the frame and sends exactly one upward accessibility gesture. A second automatic gesture is forbidden until Android reports a real scroll. If the feed does not advance, the same item is not swiped repeatedly; Magen falls back to the normal hard visual block. A burst circuit breaker stops automatic scrolling after 4 unsafe skips inside 15 seconds and pauses auto-skip for 20 seconds.

Supported surfaces: TikTok, YouTube Shorts, Instagram/Facebook Reels, and Snapchat Spotlight. TikTok is recognized by package; the others require their short-form UI marker so ordinary feeds are not auto-scrolled.


- Public Magen control plane remains `https://51.20.205.229:8443`; no public MITM proxy port is opened and port 443 is untouched.
- There is **no explicit localhost proxy**. The old `127.0.0.1:18082` path was removed because another local app could potentially use it as a VPN bypass.
- Transparent TLS path: the Full-Tunnel `TcpRelay` reads SNI and redirects eligible port-443 flows to private loopback `127.0.0.1:18083`.
- A **device-bound HTTPS-inspection Root CA** is installed automatically only for Device Owner/Profile Owner; unmanaged Android 11+ requires manual CA installation in system Settings. Each enrolled device receives a different trust root.
- Every per-device CA private key stays inside the isolated VPS `magen-mitm-signer` store; the phone creates a per-host ephemeral EC P-256 key and sends only its public SPKI for a short-lived exact-host certificate.
- Sensitive login/payment/banking/health/password-manager hosts are always tunneled end-to-end and the signer refuses to issue certificates for them.
- Certificate pinning is not bypassed. Incompatible flows receive a short-lived hashed compatibility fallback and are tunnelled encrypted on the next connection.
- Request/response bodies, cookies, credentials, tokens and complete URLs are not persisted or sent to the VPS. Path filtering is local; visual filtering remains on-device.

גרסת Android זו מותאמת לשרת Magen ב־`https://51.20.205.229:8443`.

## בניית APK ב-Windows

לגרסת production משתמשים רק ב:

```bat
BUILD_APK_ON_WINDOWS.bat
```

הבונה:
- מאתר JDK 17/21 ו־Android SDK.
- מאמת את מודל Visual Shield ואת ה־SHA-256 שלו.
- יוצר בפעם הראשונה מפתח חתימת Release קבוע בתוך `.magen-private`.
- מחשב ומזריק fingerprint של תעודת החתימה ל־IntegrityGuard.
- מריץ `verify.py --strict` ובדיקות `testReleaseUnitTest`.
- בונה `assembleRelease`, ולא Debug.
- מאמת את חתימת ה־APK עם `apksigner` ומפיק SHA-256.

**חשוב:** יש לגבות את כל `.magen-private` במקום מאובטח. אובדן מפתח החתימה ימנע עדכון חלק של APK שכבר הותקן.

`build-debug.sh` מיועד לפיתוח בלבד ואינו קובץ הפצה.

## חיבור לשרת קיים

ה־source המצורף כבר מזווג לשרת הקיים `51.20.205.229:8443` באמצעות ה־CA הציבורי ומפתח אימות חתימות השרת.

## חיבור לשרת Fresh Install חדש

התקנה חדשה של ה־VPS מייצרת PKI חדש. לאחר ההתקנה, חלץ את pairing archive שנוצר בשרת והרץ ב־Windows:

```bat
IMPORT_SERVER_PAIRING_ON_WINDOWS.bat C:\path\to\pairing-folder
BUILD_APK_ON_WINDOWS.bat
```

הייבוא מחליף רק חומר ציבורי: CA, כתובת שרת ומפתח ציבורי לאימות חתימות. מפתחות פרטיים של השרת אינם נכנסים ל־APK.

## VPN והגנת עקיפה

ב־v4.5.1 Full Tunnel הוא מצב production חובה. המערכת אינה יורדת אוטומטית ל־DNS-only לאחר תקלה, כדי לא לפתוח נתיב עקיפה.

כרגע IPv6 נתפס על ידי ה־TUN ונחסם fail-closed. זו החלטת אבטחה מכוונת עד שתהיה תמיכת relay מלאה ב־IPv6; לכן רשת/אפליקציה שתלויה רק ב־IPv6 עלולה לא לעבוד.

## Device Owner — ההגנה החזקה ביותר באנדרואיד

כדי ש־Android עצמו יאכוף Always-On VPN + Lockdown ויגביל שינויי VPN/Private DNS, ניתן לפרוס את Magen כ־Device Owner:

```bat
PROVISION_DEVICE_OWNER_ON_WINDOWS.bat
```

Android בדרך כלל מאפשר `set-device-owner` רק במכשיר חדש/מאופס לפני הוספת חשבונות. הסקריפט לא עוקף את מגבלות Android; אם המערכת מסרבת, הוא נכשל במפורש.

גם בלי Device Owner קיימות שכבות זיהוי/שחזור, אבל אפליקציה רגילה אינה יכולה להבטיח שליטה מוחלטת על הגדרות מערכת של Android.

## פרטיות ושרת

אין Telegram bot, token/chat-id או מספר "שותף" באפליקציה. אירועי אבטחה נשלחים ל־VPS המאומת. Telegram יכול להופיע רק כמקור תוכן שה־Accessibility מנתח, לא כערוץ התראות.

## Content Intelligence + Reliability v4.4

החלטות חסימה מדומיין, DeepSeek/טקסט ו־Visual Shield מתכנסות ל־Content Incident pipeline אחד. לכל Event/Incident יש מזהה client ייחודי, כך ש־retry לאחר אובדן תשובה אינו יוצר רשומה כפולה.

ה־heartbeat מדווח גם process instance, רצף כשלי שרת, VPN restarts, Full Tunnel, Device Owner, גרסת/מקור/כמות Blocklist ומדדי Intelligence. `NetworkCallback` מעיר את הקשר מיד כש־Wi‑Fi/סלולר חוזרים, עם backoff כאשר השרת אינו זמין.

Blocklist ב־production מתקבל רק כ־snapshot חתום מה־VPS. הטלפון שומר last-known-good ואינו מחליף אותו ישירות מרשימות ציבוריות לא חתומות.
