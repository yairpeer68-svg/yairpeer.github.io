# Magen Phone 3.0.4

אפליקציית Android לסינון תוכן והקשחת המכשיר, המחוברת לשרת Magen פרטי.

## שכבות הגנה

- VPN מקומי לסינון DNS/SNI ותעבורת רשת נתמכת.
- Accessibility לזיהוי תוכן גלוי, חסימת אפליקציות והגנה על מסכי מערכת רגישים.
- Device Admin לנעילת המכשיר בעת ניסיון ביטול.
- Device Owner אופציונלי לאכיפת OS חזקה: Always-On VPN + lockdown, חסימת שינוי VPN/Private DNS, חסימת app controls/safe boot/debugging והסרה.
- רשימת חסימה חתומה מה-VPS עם fallback ל-UT1/StevenBlack.
- VPS Intelligence + DeepSeek עבור דומיינים וטקסט גלוי לא מוכר.
- אירועי אבטחה ומשפטי חיזוק עוברים דרך ה-VPS החתום בלבד.

## Telegram

Telegram הוא מקור תוכן בלבד: טקסט/כיתובים גלויים שנחשפים דרך Accessibility יכולים להישלח ל-VPS/DeepSeek. אין בוט Telegram, אין token/chat-id ואין ערוץ התראות דרך Telegram.

DeepSeek הנוכחי הוא מסלול טקסט. תמונה/וידאו ללא טקסט גלוי אינם מסווגים על-ידי המסלול הזה.

## Build ב-Windows

פתח את התיקייה ולחץ על `BUILD_APK_ON_WINDOWS.bat`.
הסקריפט מאתר Android Studio/JDK/SDK, מריץ `verify.py --strict`, בדיקות Gradle ובונה `magen-v3.0.4-debug.apk`.

## Device Owner

This v4 build is intentionally deployed without Device Owner. No Device Owner provisioning script is included.

## VPS

ברירת המחדל של הזוג הזה היא `https://51.20.205.229:8443` עם CA ומפתח חתימה שמותאמים ל-ZIP של שרת Magen.
