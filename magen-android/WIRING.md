# מדריך חיבור — הרכיבים החדשים

כל הקבצים כתובים ומחוברים לממשקים הקיימים (MagenApp, NotificationHelper,
BehaviorAnalyzer). מה שנשאר זה **הצהרות ב-Manifest** ו**קריאות אינטגרציה**
של שורה-שתיים בכל מקום. הכל למטה.

> ⚠️ אף אחד מהקבצים לא נבדק על מכשיר אמיתי. חובה לבדוק — במיוחד ה-VPN.

---

## 1. AndroidManifest.xml

### הרשאות (אם עוד לא קיימות)
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### BootReceiver
```xml
<receiver
    android:name=".service.BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter android:priority="1000">
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
        <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

### AccountabilityReporter (JobService)
```xml
<service
    android:name=".service.AccountabilityReporter"
    android:permission="android.permission.BIND_JOB_SERVICE"
    android:exported="false" />
```

---

## 2. חיבור SecurityGuard + IntegrityGuard לבדיקות התקופתיות

בתוך `TamperDetectorService.runChecks()` — הוסיפי בסוף:
```java
// בדיקות אבטחה קשות (ADB / root / חתימה / שעון)
if (SecurityGuard.runSecurityChecks(this)) {
    AccountabilityReporter.recordSecurityAlert(this);
}
if (IntegrityGuard.runIntegrityChecks(this)) {
    AccountabilityReporter.recordSecurityAlert(this);
}
```

ובתוך `MagenWatchdogJob.onStartJob()` — הוסיפי:
```java
ServiceRevival.reviveAll(getApplicationContext());
SecurityGuard.runSecurityChecks(getApplicationContext());
IntegrityGuard.runIntegrityChecks(getApplicationContext());
```

---

## 3. אתחול בהפעלה — בתוך MagenApp.onCreate()

```java
// קו בסיס לשעון (עמידות לשינוי שעון ידני)
IntegrityGuard.initClockBaseline(this);

// טעינת רשימת סינון מרוחקת מה-cache
RemoteBlocklist.loadFromCache(this);

// תזמון דוח אחריות יומי
AccountabilityReporter.schedule(this);
```

---

## 4. שימוש בזמן אמין במקום שעון מקומי

בכל מקום שמסתמך על `System.currentTimeMillis()` לצורך strict-mode/geofence
(למשל בדיקת "עד מתי strict"), החליפי ל:
```java
long now = IntegrityGuard.getTrustedTimeApprox(context);
```
כך שינוי ידני של שעון המערכת לא יעקוף את ההגבלה.

---

## 5. שימוש ברשימה המרוחקת במסננים

ב-`MagenAccessibilityService` (בבדיקת URL/host) וב-VPN אם תרצי — הוסיפי:
```java
if (RemoteBlocklist.isBlocked(host)) {
    // חסום כרגיל
}
```

עדכון הרשימה ברקע (פעם ביום) — הוסיפי ל-Watchdog:
```java
new Thread(() -> RemoteBlocklist.update(getApplicationContext())).start();
```
ואת ה-URLs האמיתיים ב-`RemoteBlocklist.LIST_URL` / `HASH_URL`.

---

## 6. בדיקת חתימת APK — איך משיגים את ה-hash

הריצי פעם אחת על מכשיר אחרי התקנת ה-release, וקחי מה-Logcat את הפלט של:
```java
Log.d("SIG", IntegrityGuard.getOwnSignatureSha256(context));
```
העתיקי את המחרוזת ל-`IntegrityGuard.EXPECTED_SIG_SHA256`. כל עוד השדה ריק —
בדיקת החתימה מדלגת (לא תיכשל בטעות).

> חלופה מ-CLI:
> `keytool -list -v -keystore release.jks` ואז לקחת את ה-SHA-256 (בלי נקודתיים).

---

## 7. חיבור דוח האחריות למייל/SMS (אופציונלי)

`AccountabilityReporter` שולח דרך `NotificationHelper.notifyPartnerDigest()`.
כדי לשלוח בפועל למייל/SMS של השותף, ממשי את המתודה הזו ב-NotificationHelper
(יש לך כבר `notifyPartnerUrgent` להשוואה). לדוגמה — SMS:
```java
SmsManager.getDefault().sendTextMessage(partnerPhone, null, digest, null, null);
```
(דורש הרשאת SEND_SMS שכבר מוצהרת אצלך).

---

## סיכום מה נוסף

| קובץ | מה נותן | סטטוס |
|------|---------|-------|
| MagenVpnService (עודכן) | סגירת דליפת IPv6 + DNS-over-TCP + DoT | דורש בדיקת מכשיר |
| ServiceRevival | החייאה מרכזית של רכיבים | מוכן |
| BootReceiver | הקמה אחרי אתחול/עדכון | דורש הצהרת Manifest |
| IntegrityGuard | זיהוי root + חתימה + שעון | דורש מילוי חתימה |
| SecurityGuard | זיהוי ADB/dev-options | מוכן (מהסבב הקודם) |
| AccountabilityReporter | דוח תקופתי לשותף | דורש הצהרת Manifest |
| RemoteBlocklist | רשימה מתעדכנת מרחוק | דורש URL + בדיקת רשת |

**מה שעדיין דורש תשתית/זמן (לא קוד):** סיווג תמונות ML (ImageScanService המת),
בדיקות שדה על מגוון מכשירים, ואירוח ה-endpoint לרשימה המרוחקת.
