# סנגור — אפליקציית Flutter

לקוח Android/iOS לעוזר משפטי דיגיטלי לדין הישראלי. עובד מול
[`sanegor-backend`](../sanegor-backend).

> **המערכת אינה מספקת ייעוץ משפטי.** כל תשובה היא מידע כללי בלבד.

---

## הפעלה

```bash
flutter pub get

# מול backend מקומי (10.0.2.2 = המארח, מנקודת המבט של אמולטור Android)
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8000

# מול שרת אמיתי
flutter run --dart-define=API_BASE_URL=https://api.example.com
```

כתובת השרת מגיעה מ-`--dart-define` בלבד. אין כתובות או מפתחות מהודקים בקוד,
ולכן build של release לא נושא איתו הגדרות פיתוח.

### בנייה

```bash
# APK להתקנה ידנית
flutter build apk --release --dart-define=API_BASE_URL=https://api.example.com

# APK מפוצל לפי ארכיטקטורה (קובץ קטן יותר לכל מכשיר)
flutter build apk --release --split-per-abi --dart-define=API_BASE_URL=...

# App Bundle ל-Google Play
flutter build appbundle --release --dart-define=API_BASE_URL=...
```

### חתימה

צרו `android/key.properties` (לא נכנס ל-git):

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyPassword=...
keyAlias=upload
```

אם הקובץ לא קיים, build של release ייחתם בחתימת debug — נוח ל-CI, לא מתאים
לפרסום.

### הרצה מול שרת על המחשב שלך (WiFi)

אפשר להריץ את ה-backend על המחשב ולהתחבר אליו מהטלפון באותה רשת:

```bash
# במחשב, בתיקיית sanegor-backend
./scripts/serve-lan.sh          # מאזין ל-0.0.0.0 ומדפיס את הכתובת לתת לטלפון

# בתיקיית sanegor-app
API_BASE_URL=http://192.168.1.20:8000 ./scripts/run-local.sh run
```

**ב-WSL2 יש מלכודת אחת.** שירות שרץ ב-WSL2 נגיש מ-Windows דרך localhost, אבל
**לא** ממכשירים אחרים ברשת — כי הטלפון מדבר עם Windows, ו-Windows לא מעביר
את הפורט פנימה ל-WSL2. `serve-lan.sh` מזהה את זה ומדפיס בדיוק מה לעשות. שתי
דרכים:

* **networkingMode=mirrored** ב-`%UserProfile%\.wslconfig` — הכי נקי, דורש
  Windows 11 ו-WSL 2.0+. אחרי השינוי: `wsl --shutdown`.
* **netsh portproxy** — עובד בכל גרסה, אבל ה-IP של WSL2 מתחלף בכל הפעלה
  ולכן צריך לחזור על זה.

בשתי הדרכים צריך לפתוח את חומת האש פעם אחת:

```powershell
New-NetFirewallRule -DisplayName "Sanegor 8000" -Direction Inbound `
    -LocalPort 8000 -Protocol TCP -Action Allow
```

**חשוב:** HTTP רגיל לכתובת LAN עובד רק ב-build של debug. ה-release מסרב
לתעבורה לא מוצפנת לכל מה שאינו loopback, וזה מכוון — ראו
`android/app/src/debug/AndroidManifest.xml`.

**מגבלה:** זה עובד רק כשהטלפון והמחשב על אותה רשת. יצאת מהבית — אין שרת.
למצב שעובד מכל מקום בלי לשלם על VPS, ראו את סעיף "שרת ביתי נגיש מבחוץ"
ב-README של ה-backend.

### בדיקות

```bash
flutter test
flutter analyze
```

---

## ארכיטקטורה

```
lib/
├── core/
│   ├── config/      AppConfig — כתובות, timeouts, מגבלות
│   ├── network/     ApiClient (Dio + refresh), SseClient, ApiException
│   ├── storage/     SecureStore — אסימונים באחסון מוצפן
│   ├── router/      GoRouter + redirect לפי מצב ההתחברות
│   ├── theme/       Material 3, בהיר/כהה, פלטת צבעים מקורית
│   └── providers    Riverpod: תשתית ומאגרים
├── features/<feature>/
│   ├── domain/      מודלים טהורים, ללא תלות ב-Flutter
│   ├── data/        Repository — היחיד שמדבר עם הרשת
│   └── presentation/ Controller (StateNotifier) + מסכים
└── shared/          ווידג׳טים וכלים חוצי-מסכים
```

`domain` לא יודע על `data`, ו-`data` לא יודע על `presentation`. מסך אף פעם
לא קורא ל-`ApiClient` ישירות.

---

## מה כדאי להכיר בקוד

### RTL אמיתי

`Directionality` נכפה ל-RTL ברמת ה-`MaterialApp`, ולא נגזר מהשפה של המכשיר —
המוצר עברי, וגם משתמש עם טלפון באנגלית צריך פריסה נכונה. שדות שמכילים תוכן
לטיני (דוא״ל, סיסמה, טלפון, סכומים) מסומנים `TextDirection.ltr` נקודתית, אחרת
הסמן והטקסט מתהפכים.

### Streaming

`SseClient` מרכיב מחדש פריימים מתוך chunks של בייטים: מפצל על שורה ריקה
ומחזיק חלק לא שלם בבאפר. פיצול נאיבי על `\n` היה משבש כל תשובה שבה delta נחתך
בגבול chunk.

`ChatController` מוסיף בועה ריקה של assistant מיד עם השליחה וממלא אותה
מהזרם; כפתור השליחה הופך ל"עצור" כל עוד התשובה זורמת.

### אסמכתאות

השרת מחזיר `sources` באירוע ה-`start` — עוד לפני שהמודל התחיל לכתוב — כך
שהמשתמש רואה על מה התשובה מתבססת מהפריים הראשון. `CitationCard` מציג רק
מקורות שהשרת אימת מול המאגר שלו; האפליקציה לא מייצרת אסמכתאות בעצמה.

כשהמאגר בצד השרת ריק, מסך החיפוש אומר זאת במפורש (`corpus_empty`) במקום
להציג "לא נמצאו תוצאות" — ההבדל מהותי.

### חידוש אסימון

`ApiClient` מרכז בקשות refresh מקבילות ל-future אחד. בלי זה, שלוש בקשות
שנכשלות ב-401 בו-זמנית היו יוצרות שלוש רוטציות, והשרת (שמבטל אסימון refresh
לאחר שימוש יחיד) היה מנתק את המשתמש.

### טפסים מהשרת

`TemplateFormScreen` בונה את הטופס מתוך הגדרת התבנית שהשרת מחזיר. הוספת סוג
חוזה חדש בשרת מופיעה באפליקציה בלי גרסה חדשה.

שדה חובה שנשאר ריק אינו חוסם — המשתמש מקבל אזהרה, והמסמך מסמן `______`
במקום. זה מכוון: המערכת לא ממציאה שם צד או תאריך.

---

## מסכים

| מסך | תיאור |
|---|---|
| Splash | טעינת session, אנימציה, לוגו מצויר בקוד |
| Login / Register / Forgot password | אימות, חוזק סיסמה, ולידציה בעברית |
| Chat | Streaming, Markdown, קבצים, הקלדה קולית, נעיצה, ייצוא |
| Documents | העלאה, צילום, OCR, מחיקה |
| Analysis | סיכום, ציוני סיכון ומורכבות, סיכונים, מועדים, המלצות |
| Contracts / Letters | 9 תבניות חוזים, 10 תבניות מכתבים |
| Generated | תצוגת המסמך, שדות חסרים, ייצוא PDF/DOCX |
| Search | חיפוש חקיקה ופסיקה, סינון לפי ערכאה/תחום/תאריך |
| History | שיחות, מועדפים, נעיצה, מחיקה |
| Profile / Settings | פרופיל, ערכת נושא, Streaming, מצב המאגר |

---

## הרשאות

| הרשאה | למה |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | תקשורת עם השרת |
| `CAMERA` | צילום מסמך לסריקה ו-OCR |
| `RECORD_AUDIO` | הקלדה קולית של שאלות |
| `POST_NOTIFICATIONS` | תזכורות על מועדים שזוהו בניתוח |

מצלמה ומיקרופון מוגדרים `required="false"` — האפליקציה עובדת גם בלעדיהם.

## אבטחה בצד הלקוח

* אסימונים ב-`flutter_secure_storage` (Android Keystore / iOS Keychain).
* `usesCleartextTraffic="false"` — HTTP חסום ב-release. חריג ל-`10.0.2.2`
  קיים כדי לאפשר פיתוח מול שרת מקומי.
* גיבוי ענן והעברת מכשיר מושבתים (`data_extraction_rules.xml`), אחרת session
  חי היה עובר למכשיר אחר.
* אין מפתחות בקוד; כתובת השרת מגיעה מ-`--dart-define`.

---

## גופנים

`pubspec.yaml` מצהיר על משפחת Heebo מ-`assets/fonts/`. הקבצים עצמם אינם
כלולים ב-repo מטעמי רישוי. הורידו אותם מ-Google Fonts ושמרו כ-
`Heebo-Regular.ttf`, `Heebo-Medium.ttf`, `Heebo-Bold.ttf`.

עד שתעשו זאת, `google_fonts` מוריד את Heebo בזמן ריצה, כך שהאפליקציה נראית
נכון גם בלי הקבצים — אך הורדה ראשונה דורשת רשת. לבנייה לייצור מומלץ לצרף את
הקבצים.
