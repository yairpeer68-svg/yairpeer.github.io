# צל דיגיטלי — הפעלת ניטור מתמשך (שלב 4) 🛰️

זה מה שהופך את הפרימיום למנוי שמתחדש: המערכת בודקת שוב ושוב את המיילים בניטור, ומתריעה במייל כשמתגלה דליפה חדשה. **זמן: ~10 דקות.**

## שלב 1 — עדכון הסכמה
SQL Editor → New query → הדבק את `schema-monitoring.sql` → Run.
(מוסיף עמודת `email` ו-`last_checked` לטבלת הניטור.)

## שלב 2 — חשבון Resend לשליחת מיילים (חינם)
1. היכנס ל-https://resend.com → הרשמה (חינם, 3,000 מיילים בחודש)
2. **API Keys → Create API Key** → העתק (מתחיל ב-`re_`)
3. *(אופציונלי בהמשך)* אם יש לך דומיין — אמת אותו תחת **Domains** כדי לשלוח מכתובת משלך. עד אז המערכת שולחת מ-`onboarding@resend.dev`.

## שלב 3 — פריסת פונקציית הסריפ
Edge Functions → Deploy a new function → Via Editor → שם: `monitor-sweep` →
הדבק את `functions/monitor-sweep/index.ts` → Deploy.

## שלב 4 — סודות
Edge Functions → Secrets → הוסף:
- `RESEND_API_KEY` = המפתח מ-Resend
- `CRON_SECRET` = המצא מחרוזת אקראית כלשהי (למשל `tzel-sweep-8f3k9x`) — מגן על ההפעלה
- `ALERT_FROM` = `צל דיגיטלי <onboarding@resend.dev>` (או הכתובת שלך אחרי אימות דומיין)
- (`HIBP_API_KEY` כבר קיים משלב 2 — משותף)

## שלב 5 — תזמון יומי (Cron)
ב-Supabase: **Integrations → Cron** (או Database → Cron Jobs) → **Create job**:
- Name: `daily-breach-sweep`
- Schedule: `0 6 * * *` (כל יום ב-06:00)
- Type: **Supabase Edge Function** → בחר `monitor-sweep`
- הוסף header: `x-cron-secret` עם הערך של `CRON_SECRET` משלב 4
- Save

---

## מה קורה עכשיו
- משתמש פרימיום שלוחץ "הפעל ניטור" באפליקציה → המייל שלו נרשם בטבלת `monitors`.
- כל בוקר `monitor-sweep` בודק את כל המיילים הפעילים מול HIBP.
- אם מספר הדליפות של מייל **עלה** מאז הבדיקה הקודמת → נשלחת אליו התראת מייל, ומספר הדליפות מתעדכן.
- המשתמש יכול לכבות ניטור בכל רגע (מסמן `active=false`).

## בדיקה מהירה
בקונסולת ה-Edge Functions אפשר להריץ את `monitor-sweep` ידנית (Invoke) עם header `x-cron-secret` — התשובה תחזיר `{ checked, alerts }`.
