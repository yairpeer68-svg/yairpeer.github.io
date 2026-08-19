# בנייה ב-GitHub Actions

ה-APK נבנה אוטומטית על ידי `.github/workflows/build-ghosteye-apk.yml`
(JDK 17 + Gradle 8.9, `gradle assembleDebug -PAPI_BASE_URL=https://51.20.205.229`).

## שינויים שנעשו לצורך הבנייה בשרת

מקור: `ghosteyephone10.1.1verified51.20.205.229.zip` (גרסה 10.1.1, versionCode 15).
הקוד נשמר כפי שהוא, למעט:

1. **`gradle.properties` (חדש)** — לא היה בארכיון. בלי `android.useAndroidX=true`
   הבנייה נכשלת מיד עם תלויות AndroidX.
2. **`app/build.gradle.kts`** — הוספת `compileOptions` ו-`kotlinOptions` ליעד JVM 17,
   כדי למנוע אי-התאמה בין יעד ה-Java ליעד ה-Kotlin.
3. **`ApiClient.kt`** (שורות 75, 76, 82) — `throw@withContext X` הוחלף ב-`throw X`.
   ל-Kotlin אין תחביר `throw@label` (רק `return@label`), והבנייה נכשלה שם עם
   `Unresolved reference 'withContext'`. זריקת חריגה מתוך בלוק `withContext` מתפשטת
   החוצה בדיוק כפי שהקוד התכוון.
4. **`.gitignore` (חדש)** — התעלמות מ-`build/`, `.gradle/`, `local.properties`.

בעקבות סעיפים 2–3, `SHA256_MANIFEST.txt` שמקורו בארכיון כבר לא תואם לשני הקבצים
`app/build.gradle.kts` ו-`ApiClient.kt`. שאר הקבצים ללא שינוי.

## הורדת ה-APK

* לשונית **Actions** → הריצה האחרונה → קובץ `ghosteye-apk` (נשמר 30 יום).
* לאחר מיזוג ל-`main` מתפרסם גם Release בתג `ghosteye-latest` עם `ghosteye.apk`
  להורדה ישירה לטלפון.
