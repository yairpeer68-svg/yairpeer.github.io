# yairpeer.github.io

אוסף האפליקציות והכלים של יאיר. האתר עצמו הוא דף נחיתה פשוט
([`index.html`](index.html)) שמקשר לכל אחת מהאפליקציות.

## אפליקציות

| אפליקציה | תיאור | סוג | תיקייה |
|----------|-------|-----|--------|
| 🕵️ **צל דיגיטלי** | בדיקת דליפות וטביעת רגל דיגיטלית עם מדריך הגנה | PWA + אנדרואיד | [`tzel/`](tzel/) · [`tzel-android/`](tzel-android/) · [`tzel-backend/`](tzel-backend/) |
| 🏛️ **פקיד** | צילום מכתב רשמי וקבלת הסבר בעברית פשוטה | PWA | [`pakid/`](pakid/) · [`pakid-backend/`](pakid-backend/) |
| 🛡️ **תוקף** | התראות לפני שפג תוקף (טסט, דרכון, ביטוח, רישיון) | PWA + אנדרואיד | [`tokef/`](tokef/) · [`tokef-android/`](tokef-android/) |
| 💸 **מנוימטר** | ריכוז כל המנויים במקום אחד | PWA + אנדרואיד | [`subs/`](subs/) · [`minuymeter-android/`](minuymeter-android/) |
| 🛡️ **שומר הברית** | מסנן תוכן ואפליקציית אחריות לאנדרואיד | אנדרואיד | [`magen/`](magen/) · [`magen-android/`](magen-android/) |

## בנייה — APK לאנדרואיד

בניית ה‑APK מתבצעת ב‑GitHub Actions (בלי צורך במחשב חזק או ב‑Android
Studio). כל אפליקציית אנדרואיד עם workflow משלה תחת
[`.github/workflows/`](.github/workflows/):

- דחיפה לתיקיית האפליקציה מפעילה בנייה אוטומטית.
- ה‑APK עולה כ‑artifact בהרצה, וגם מתפרסם כ‑release (`*-latest`) בדחיפה ל‑`main`.
- אפשר להריץ ידנית מטאב **Actions** → הבחירה ב‑workflow → **Run workflow**.

עבור **שומר הברית** ה‑workflow מריץ קודם בדיקה סטטית
([`magen-android/verify.py`](magen-android/verify.py) במצב `--strict`) ואת
בדיקות היחידה, ורק אז בונה — כך תקלות נתפסות בשניות במקום אחרי בנייה מלאה.
ה‑APK שלו מתפרסם תחת התג **`dev-latest`** בשם `shomer-habrit.apk` (וגם עותק
עם ה‑commit בשם, כדי שמטמון לא יגיש גרסה ישנה) — זהו הקישור שאליו מפנה
[`magen/`](magen/).

## מבנה

- `*/` (אותיות קטנות) — ה‑PWA / דף הנחיתה המוגש דרך GitHub Pages.
- `*-android/` — פרויקט אנדרואיד (Java + Gradle).
- `*-backend/` — פונקציות ו‑schema של השרת (Supabase Edge Functions).
