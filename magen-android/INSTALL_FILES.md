# מדריך התקנה — הצבת הקבצים ובניית APK דרך GitHub

בנייה בענן, חינם, בלי מחשב חזק ובלי Android Studio. בסוף מקבלים APK debug מוכן להתקנה.

---

## שלב 1 — הצבת 12 הקבצים במקומות הנכונים

כל הקבצים החדשים/המעודכנים הולכים תחת `app/src/main/java/com/magen/family/`.
**מחליפים** קבצים שכבר קיימים (VPN, DeviceAdmin, Watchdog) ו**מוסיפים** את החדשים.

| קובץ | יעד מדויק | פעולה |
|------|-----------|-------|
| MagenVpnService.java | `service/MagenVpnService.java` | מחליף קיים |
| MagenDeviceAdmin.java | `admin/MagenDeviceAdmin.java` | מחליף קיים |
| MagenWatchdogJob.java | `service/MagenWatchdogJob.java` | מחליף קיים |
| SecurityGuard.java | `service/SecurityGuard.java` | חדש |
| IntegrityGuard.java | `service/IntegrityGuard.java` | חדש |
| ServiceRevival.java | `service/ServiceRevival.java` | חדש |
| BootReceiver.java | `service/BootReceiver.java` | חדש (כבר מוצהר ב-Manifest!) |
| AccountabilityReporter.java | `service/AccountabilityReporter.java` | חדש |
| RemoteBlocklist.java | `service/RemoteBlocklist.java` | חדש |
| DomainBloomFilter.java | `service/DomainBloomFilter.java` | חדש |
| FloatingBadgeService.java | `service/FloatingBadgeService.java` | חדש |

> ה-package בראש כל קובץ כבר תואם (`com.magen.family.service` / `.admin`) — לא לשנות.

---

## שלב 2 — שתי הוספות ל-AndroidManifest.xml

רוב ההצהרות כבר קיימות אצלך (BootReceiver, VPN, KillSwitch, Watchdog, DeviceAdmin
וכל ההרשאות). חסרים רק **שני שירותים**. הוסיפי אותם בתוך `<application>...</application>`,
ליד שאר ה-`<service>`:

```xml
<!-- מדבקה צפה "מוגן" -->
<service android:name=".service.FloatingBadgeService" android:exported="false"
    android:foregroundServiceType="specialUse">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="protection_status_badge"/>
</service>

<!-- דוח אחריות יומי לשותף -->
<service android:name=".service.AccountabilityReporter"
    android:permission="android.permission.BIND_JOB_SERVICE"
    android:exported="false"/>
```

זהו. כל השאר כבר מוצהר. (אם אי פעם תרצי — אפשר להסיר את ה-receiver הישן
`MagenVpnWatchdog` שחופף חלקית ל-BootReceiver החדש, אבל זה לא חובה.)

---

## שלב 3 — שלוש שורות אתחול ב-MagenApp.onCreate()

בתוך `MagenApp.java`, במתודה `onCreate()`, אחרי האתחול הקיים, הוסיפי:

```java
// טעינת רשימת הדומיינים החסומים מה-cache (מהיר)
RemoteBlocklist.loadFromCache(this);

// קו בסיס לשעון (עמידות לשינוי שעון ידני)
IntegrityGuard.initClockBaseline(this);

// תזמון דוח אחריות יומי
AccountabilityReporter.schedule(this);

// הפעלת המדבקה הצפה
try {
    android.content.Intent badge = new android.content.Intent(this, com.magen.family.service.FloatingBadgeService.class);
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        startForegroundService(badge);
    else startService(badge);
} catch (Exception ignored) {}
```

וב-`MagenAccessibilityService` / VPN, בבדיקת ה-host, הוסיפי את השכבה החדשה:
```java
if (RemoteBlocklist.isBlocked(host)) { /* חסום כרגיל */ }
```

---

## שלב 4 — העלאה ל-GitHub ובנייה

1. צרי repo חדש ב-GitHub (פרטי אם תרצי).
2. העלי את **כל הפרויקט** (לא רק הקבצים החדשים — גם build.gradle, settings.gradle,
   AndroidManifest, res/, וכו').
3. צרי תיקייה `.github/workflows/` והעלי לתוכה את `build.yml`.
4. ה-workflow ירוץ אוטומטית בכל push. אפשר גם להריץ ידנית:
   טאב **Actions** → **Build Debug APK** → **Run workflow**.
5. כשמסתיים (בהצלחה — עיגול ירוק), פתחי את ההרצה → למטה תחת **Artifacts** →
   הורדת **magen-debug-apk**. בפנים ה-APK.

---

## שלב 5 — התקנה על הטלפון

1. העבירי את ה-APK לטלפון (או הורידי ישירות מ-GitHub בדפדפן הטלפון).
2. אפשרי "התקנה ממקורות לא ידועים" עבור הדפדפן/מנהל הקבצים.
3. התקיני, ואז עברי את מסך ההרשאות (8 ההרשאות שכבר בנוי).

---

## פתרון תקלות בנייה

- **"SDK location not found"** — ה-workflow מטפל בזה (setup-android). אם עדיין,
  ודאי ש-`local.properties` **לא** הועלה ל-repo (הוא ספציפי למחשב שלך).
- **"Could not find method ... AGP"** — ודאי שהעלית את `build.gradle` ו-`settings.gradle`
  המקוריים.
- **כשל קומפילציה על אחד הקבצים** — ודאי שכל 11 הקבצים במקום; הם תלויים זה בזה
  (למשל BootReceiver קורא ל-SecurityGuard ו-IntegrityGuard).
- **חסר `gradle-wrapper`** — לא רלוונטי; ה-workflow משתמש ב-Gradle 8.4 מותקן ישירות.

> ⚠️ תזכורת: זה APK debug (לא חתום לפרודקשן). מצוין לבדיקות והתקנה עצמית.
> ל-Play Store / הפצה רחבה צריך build חתום (release + keystore).
