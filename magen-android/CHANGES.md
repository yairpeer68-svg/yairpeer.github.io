# סבב חיזוק — מה השתנה

מסמך זה מתעד את השינויים מול הגרסה שהתקבלה ב-`magen_v2_FULL_project.zip`.

---

## 🔴 באגים קריטיים שתוקנו

### 1. קריסה מובטחת כל 15 דקות
`ServiceRevival`, `MagenWatchdogJob`, `MagenVpnWatchdog` הפעילו את
`MagenVpnService`, `TamperDetectorService` ו-`MagenKillSwitch` דרך
`startForegroundService()`. אף אחד משלושת השירותים לא קרא ל-`startForeground()`,
ולכן אנדרואיד זרק `ForegroundServiceDidNotStartInTimeException` אחרי 5 שניות.

**תיקון:** `MagenVpnService` הפך ל-foreground service אמיתי (כולל
`foregroundServiceType` ב-Manifest, שנדרש ב-Android 14). שני האחרים מופעלים
עכשיו ב-`startService()` רגיל.

### 2. נעילת ה-KillSwitch נעקפה בשתי לחיצות
`PinActivity.rearmKillSwitch()` החזיר את הנעילה רק אם `unlock_at > now`.
במצב "נעול עד PIN" — בדיוק המצב שנוצר ב-`onDisableRequested`, כלומר בניסיון
להסיר את מנהל המכשיר — הערך היה 0, ולכן לחיצה על "פתיחה עם קוד" ואז BACK
פתחה את המכשיר בלי שום קוד.

**תיקון:** דגל `require_pin` נשמר ב-prefs ומוחזר גם הוא. בנוסף נוסף מעקב
`authenticated` כדי שהנעילה תוחזר גם ביציאה דרך HOME.

### 3. אפס הגבלת קצב על ה-PIN
`tryVerifyRegularPin()` החזיר `false` בכישלון **בלי לקרוא ל-`countFailedAttempt()`**.
הספירה קרתה רק במסלול קוד החירום בן 6 הספרות, ולכן מנגנון "5 ניסיונות = נעילה"
היה מת לחלוטין עבור ה-PIN הראשי — 10,000 צירופים בלי שום השהיה.

**תיקון:** אחרי כישלון נקבע טיימר חסד של 1.5 שניות (כדי לאפשר המשך לקוד חירום);
אם המשתמש לא המשיך — הניסיון נספר והנעילה הפרוגרסיבית פועלת.

---

## 🌐 שכבת הרשת — נכתבה מחדש

הרכיב המרכזי של הסבב. הקוד החדש נמצא תחת `service/vpn/`.

| קובץ | תפקיד |
|------|-------|
| `VpnEngine` | ניתוב כל חבילה לשכבה המתאימה + נסיגה אוטומטית בכשל |
| `UdpRelay` | העברת UDP + סינון DNS + חסימת QUIC/DoT |
| `TcpRelay` | מכונת מצבים של TCP + סינון SNI |
| `SniParser` | חילוץ שם המארח מ-TLS ClientHello ומכותרת Host |
| `DnsMessage` | פרסור שאילתת DNS ובניית תשובת NXDOMAIN |
| `Ipv4` | בנייה וקריאה של חבילות גולמיות + checksums |
| `VpnPolicy` | הגדרות המנוע |

### מה זה סוגר

**חור ה-DNS השרירותי.** קודם ה-VPN ניתב פנימה רק ~40 כתובות IP של ספקי DNS
ידועים. שאילתה ל-resolver אחר — כל IP בעולם — פשוט לא נכנסה למנהרה והסינון
לא ראה אותה. במצב full tunnel מנותב `0.0.0.0/0` וכל שאילתת DNS עוברת דרך
המסנן, לא משנה לאן היא מכוונת.

**סינון SNI.** שם הדומיין נקרא מתוך הודעת ה-ClientHello של TLS. זה עובד
**גם כשה-DNS נעקף לחלוטין**, ולא דורש Root CA ולא שובר certificate pinning.

**QUIC.** UDP/443 נזרק, מה שמאלץ את הדפדפן ליפול חזרה ל-TCP — שם ה-SNI גלוי.

### מנגנון בטיחות

מצב full tunnel אומר שכל תעבורת המכשיר עוברת דרך קוד Java. באג שם = אין
אינטרנט. לכן:

* **ברירת המחדל היא כבוי.** מפעילים מ"הגנה מתקדמת" במסך הראשי.
* `VpnEngine` סופר כשלים; מעל 50 בדקה הוא מכבה את עצמו וחוזר למצב DNS-only.
* לולאת ה-restart קיבלה backoff מעריכי ומכסה של 8 ניסיונות (קודם: כל 2 שניות,
  לנצח, עם ניקוז סוללה מלא).

### הפשטות מכוונות ב-TcpRelay

אין שידור חוזר ואין טיפול בחבילות מחוץ לסדר לכיוון המכשיר. כתיבה ל-TUN היא
מסירה מקומית לקרנל — אין בה איבוד חבילות, והאיבוד האמיתי בצד האינטרנט מטופל
ממילא ע"י ה-TCP של הקרנל דרך `SocketChannel`. בקרת זרימה (חלון המכשיר) **כן**
ממומשת, אחרת הורדה גדולה מציפה את הצד השני.

---

## 🔒 הקשחה

* **`MagenDeviceAdmin.lockDeviceNow()`** — מדיניות `force-lock` הוצהרה
  ב-`device_admin.xml` מההתחלה אבל **מעולם לא נקראה**. עכשיו כיבוי נגישות או
  ניסיון להסיר את ההגנה מפעילים את מסך הנעילה האמיתי של המערכת, שאי אפשר
  לעקוף ב-BACK או ב-HOME כמו overlay.

* **`TamperWatcher`** — `ContentObserver` על `ENABLED_ACCESSIBILITY_SERVICES`
  ועל `ADB_ENABLED`. זיהוי **מיידי** במקום polling של 30 שניות / 15 דקות.

* **`InstallMarker`** — סמן שנכתב דרך MediaStore ל-`Documents/Magen/`.
  הוא שורד "נקה נתונים" (ואפילו הסרה), ולכן השילוב "סמן קיים + אין PIN" מזהה
  בדיוק את מי שאיפס את האפליקציה מההגדרות. `allowClearUserData="false"` לא
  עוזר — הוא מכובד רק לאפליקציות מערכת.

* **`HostAllowList`** — ההורה יכול לפתוח דומיין ספציפי אחרי אימות PIN. נחוץ
  כי ל-Bloom filter יש ~1% false-positive, ובלי דרך תיקון משתמשים פשוט עוקפים
  את המסנן לגמרי.

---

## 🐛 באגים נוספים שתוקנו

| רכיב | הבעיה | התיקון |
|------|-------|--------|
| `AhoCorasick` | חיפוש תת-מחרוזת חסם את `document` (מכיל "cum"), `analysis` (מכיל "anal"), `Essex`, `vacuum`, `stripe` | בדיקת גבולות מילה בתוך מעבר הסריקה, O(n) |
| `AhoCorasick` | `build()` חוזר הכפיל את רשימות ההתאמות | ה-trie נבנה מחדש מרשימת דפוסים מקורית |
| `SecurityGuard` | ADB דלוק → strict mode כל 15 דק' = **הטלפון משותק לצמיתות** | התראה בלבד, בלי strict mode |
| `SecurityGuard` / `IntegrityGuard` | SMS דחוף כל 15 דקות לנצח | throttle של 6 שעות לכל סוג אזהרה |
| `NotificationHelper` | אין תקרה גלובלית ל-SMS | לכל היותר SMS אחד ברבע שעה |
| `NotificationHelper` | `parent_phone` נקרא ומעולם לא נכתב — SMS מעולם לא נשלח | שדה במסך "הגנה מתקדמת" + בקשת הרשאה |
| `AppInstallReceiver` | `"tor"` כתת-מחרוזת תפס את Google Docs (`edi**tor**s`), `s**tor**age`, `mo**tor**ola` | רשימת חבילות מדויקת + התאמת רכיב שלם |
| `ScreenTimeService` | סכם זמן חזית של כל החבילות כולל המשגר והמערכת → מכסה נחצתה מיד | סינון מערכת/משגר + תקרת חלון יומי |
| `ScreenTimeService` | הפעיל KillSwitch כל דקה מחדש | רק בחציית המכסה |
| `AppScheduleService` | פרסור JSON + `queryAndAggregateUsageStats` בכל החלפת אפליקציה, ב-thread של הנגישות | cache של 30 שנ' ללוחות ו-60 שנ' לשימוש |
| `MagenAccessibilityService` | סריקת DOM ללא throttle באפליקציות חברתיות → ANR | throttle לכל האפליקציות + הגבלת עומק |
| `MagenAccessibilityService` | `findText("VPN")` חסם כל מסך הגדרות שהמילה מופיעה בו | דורש גם אינדיקציה למסך הגדרת VPN |
| `AccountabilityReporter` | `VPN_ATTEMPTS`/`SETTINGS_ATTEMPTS` לא אופסו → הדוח נתקע לנצח על "ניסיונות חוזרים" | איפוס אחרי כל דוח |
| `AccountabilityReporter` | `schedule()` לא נקרא מאף מקום — הדוח היומי מעולם לא רץ | נקרא מ-`MagenApp.onCreate` |
| `RemoteBlocklist` | `isBlocked()` לא נקרא מאף מקום — 3M דומיינים הורדו ולא סיננו כלום | מחובר דרך `DomainVerdict` ל-DNS, SNI ו-URL |
| `MainActivity` | `onResume` פתח את מסך ההרשאה בלולאה אינסופית | באנר קבוע + בקשה אחת בכל פעם |
| `CrashLogger` | כתב ל-`DIRECTORY_DOWNLOADS` — נכשל בשקט תחת Scoped Storage | `getExternalFilesDir()` + מכסת גודל |
| `IntegrityGuard` | סבילות שעון של 5 דק' → סנכרון NTP נראה כמניפולציה | שעה |
| `accessibility_service_config` | `packageNames=""` = "אפס אפליקציות" | הוסר |

---

## 🧹 ניקוי

* **ML Kit הוסר** — `ImageScanService` היה קוד מת לחלוטין (אף reference אחד)
  אבל גרר `text-recognition` (~10MB ל-APK).
* **`REQUEST_INSTALL_PACKAGES` הוסרה** — הרשאה מסוכנת ש-`UpdateService`
  הפסיק להשתמש בה.
* **`FileProvider` ו-`file_paths.xml` הוסרו** — לא היו בשימוש.
* **`android:persistent="true"` הוסר** — מכובד רק לאפליקציות מערכת.
* **`device_admin.xml` צומצם** ל-`force-lock` + `watch-login` בלבד.
  `wipe-data` בפרט גרם למסך אישור מפחיד שהוריד את הסיכוי שההרשאה תאושר.
* **`SCREEN_ON` הוסר מ-`MagenVpnWatchdog`** — לא נמסר ל-receiver מוצהר-Manifest.

---

## ⚠️ מה עדיין פתוח — ואי אפשר לסגור ב-Device Admin

זה מדויק, לא הערכה:

| עקיפה | מצב |
|-------|-----|
| **מצב בטוח (Safe Mode)** | לחיצה ארוכה על "כיבוי" מפעילה אתחול שבו כל אפליקציות צד-ג' מושבתות. **אין שום הגנה אפשרית.** רק Device Owner (`DISALLOW_SAFE_BOOT`) סוגר את זה |
| **משתמש שני / אורח** | פרופיל נקי בלי סינון. לא ניתן למניעה ולא לזיהוי אמין בלי Device Owner (`DISALLOW_ADD_USER`) |
| **ADB** | לא ניתן לכיבוי ע"י אפליקציה רגילה. מזהים מיידית ומדווחים |
| **Root** | עוקף הכל |
| **QUIC ל-IP ישיר** | נחסם רק כשמצב full tunnel דלוק |

**התקרה בשורה אחת:** זו שכבה שעוצרת את מי שלא באמת מנסה. עמידות אמיתית
דורשת Device Owner, וזה דורש איפוס המכשיר בהתקנה.

---

## 🧪 מה חובה לבדוק על מכשיר

הקוד לא הורץ על אנדרואיד פיזי. סדר בדיקה מומלץ:

1. **מצב DNS-only (ברירת מחדל)** — גלישה רגילה עובדת, אתר חסום נחסם.
2. **הפעלת "סינון מלא"** — זה השינוי המסוכן. בדקי לפי סדר:
   * דף HTTP רגיל נטען
   * דף HTTPS נטען
   * הורדת קובץ גדול (בודק בקרת זרימה ב-`TcpRelay`)
   * אפליקציות שאינן דפדפן (וואטסאפ, חנות) עובדות
   * אתר חסום נחסם מיד עם שגיאת חיבור
3. **`lockNow`** — כיבוי שירות הנגישות אמור לנעול את המסך תוך שנייה.
4. **"נקה נתונים"** — אחרי הפעלה מחדש אמורה להופיע התראה ונעילה.
5. **PIN** — 5 ניסיונות שגויים אמורים לנעול ל-5 דקות.
6. **סוללה** — בדקי במיוחד על שיאומי/וואן פלוס/סמסונג.
