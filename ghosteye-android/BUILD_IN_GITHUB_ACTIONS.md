# בניית האפליקציה בענן (GitHub Actions)

הקבצים המקוריים של Ghost Eye Phone 11.1.0 נשמרו כאן ללא שינוי
(אפשר לאמת: `sha256sum -c SHA256_MANIFEST.txt` — 82 קבצים).
נוספו רק שני דברים כדי שאפשר יהיה לבנות APK בלי מחשב Windows:

1. `gradle/wrapper/gradle-wrapper.jar` — היה חסר בארכיון, ובלעדיו
   `gradlew` / `gradlew.bat` לא רצים בכלל.
2. `.github/workflows/build-ghosteye-apk.yml` בשורש המאגר — בונה APK בענן.

## איך מקבלים APK

1. GitHub → לשונית **Actions** → **Build Ghost Eye APK**.
2. **Run workflow** (או פשוט לדחוף שינוי בתיקייה `ghosteye-android/`).
3. בסיום הריצה, בתחתית העמוד, מורידים את הארטיפקט **ghost-eye-apk**
   (קובץ ZIP שבתוכו `ghost-eye.apk`).
4. בטלפון: פותחים את ה-APK ומאשרים התקנה ממקור לא מוכר.

זו בניית **debug** — חתומה במפתח debug, מיועדת להתקנה ידנית בלבד.

## החלפת כתובת השרת

ברירת המחדל מוטמעת ב-`app/build.gradle.kts` (`API_BASE_URL`, חייבת HTTPS).
כדי לבנות מול כתובת אחרת בלי לשנות קוד — ב-**Run workflow** ממלאים את השדה
`api_base_url`, למשל `https://example.org`. השדה מועבר כ-`-PAPI_BASE_URL`.

## למה אין Release ציבורי

בניגוד לאפליקציות האחרות במאגר, ה-APK כאן **לא** מתפרסם כ-Release פומבי:
כתובת השרת מוטמעת בקובץ ה-APK, ופרסום פומבי היה חושף אותה לכל אחד.
הארטיפקט ב-Actions זמין רק למי שיש לו גישה למאגר.

## בנייה מקומית ב-Windows

ללא שינוי מהמקור — `BUILD_PHONE_WINDOWS.bat` לבנייה, ו-`BUILD_RELEASE_WINDOWS.bat`
לגרסה חתומה. עכשיו זה גם עובד באמת, כי `gradle-wrapper.jar` קיים.
