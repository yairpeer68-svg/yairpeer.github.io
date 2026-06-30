# הכנה לפרסום ב-Play Store

## חתימת האפליקציה (Release Signing)

האפליקציה אינה כוללת keystore בריפו (וזה נכון - אסור לעולם להעלות מפתח חתימה ל-git).
כדי לבנות גרסת release חתומה:

1. צרו keystore חדש (פעם אחת בלבד, שמרו אותו במקום בטוח מחוץ לריפו):
   ```
   keytool -genkeypair -v -keystore sherlock-release.jks -alias sherlock -keyalg RSA -keysize 2048 -validity 10000
   ```
2. העתיקו את `keystore.properties.example` ל-`keystore.properties` (בתיקיית `sherlock-android/`, לא בתוך `app/`) ומלאו את הפרטים האמיתיים. הקובץ הזה כבר ב-`.gitignore` ולא יישלח ל-git.
3. בנו AAB לפרסום:
   ```
   ./gradlew bundleRelease
   ```
   הקובץ ייווצר ב-`app/build/outputs/bundle/release/app-release.aab`.

אם `keystore.properties` לא קיים, ה-build עדיין יעבוד (build לא חתום), כדי לא לשבור build רגיל בסביבת פיתוח/CI.

## גרסה נוכחית

- `versionCode = 4`, `versionName = "4.0"` (`app/build.gradle.kts`) - יש להעלות את שניהם בכל עדכון שמועלה ל-Play Store.

## מינימיזציה וצמצום (R8/ProGuard)

- `isMinifyEnabled = true`, `isShrinkResources = true` כבר מוגדרים ב-release build type.
- כללי השמירה הרלוונטיים (Gson, ML Kit, Room) נמצאים ב-`app/proguard-rules.pro`.
- מומלץ לבנות AAB release ולהתקין אותו על מכשיר פיזי לבדיקה לפני העלאה, כדי לוודא שאף מסך לא נשבר עקב minification.

## הרשאות (Permissions)

ההרשאות שנותרו ב-Manifest כולן בשימוש בפועל:
- `INTERNET`, `ACCESS_NETWORK_STATE` - קריאות לאתרים/API ציבוריים (אין שרת משלנו).
- `CAMERA` - חיפוש לפי תמונה / השוואת פנים / OCR.
- `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` (מכשירים ישנים) - בחירת תמונות מהגלריה.
- `WRITE_EXTERNAL_STORAGE` (מכשירים ישנים בלבד, עד API 28) - שמירת ייצוא/קבצים.
- `VIBRATE` - משוב הפטי.
- `POST_NOTIFICATIONS` - התראות ניטור/חיפושים מתוזמנים.

הוסרו הרשאות שלא היו בשימוש בפועל בקוד: `USE_BIOMETRIC`, `RECORD_AUDIO`, `RECEIVE_BOOT_COMPLETED`
(חיפוש קולי משתמש ב-Intent חיצוני למנוע הדיבור של המערכת, ולא דורש הרשאת מיקרופון ישירה).

## גיבוי נתונים (Backup)

נוספו `data_extraction_rules.xml` ו-`backup_rules.xml` שמחריגים את מסד הנתונים (Room) ואת קובץ
ה-DataStore מגיבוי ענן/העברת מכשיר, כדי שנתוני חקירה רגישים לא ייצאו מהמכשיר דרך גיבוי אוטומטי.

## Data Safety Form (Play Console)

- האפליקציה לא שולחת נתונים לשרת של המפתח - אין backend משלה.
- היא מבצעת קריאות רשת ישירות לאתרים/API ציבוריים חינמיים (לפי מה שהמשתמש מחפש).
- אין SDK פרסומות, אין אנליטיקס/קראשליטיקס של צד שלישי.
- כל הנתונים (היסטוריה, מועדפים, פרויקטים, הערות) נשמרים מקומית בלבד (Room + DataStore) ולא מועברים החוצה, אלא אם המשתמש עצמו מייצא/משתף.
