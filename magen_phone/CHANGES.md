# Changelog

## 3.0.4

- אזור "אפליקציות לחסימה" הפך לכרטיס QuickAction רגיל; החיפוש/קטגוריות/רשימה מקופלים עד שלוחצים עליו.
- חלון maintenance גלובלי הוסר. כל מסך רגיש מקבל scope יחיד וקצר בלבד (Accessibility/Admin/VPN/Overlay וכו').
- ProtectionWatch ממשיך לבדוק את כל שאר ההגנות גם בזמן scope נקודתי.
- מסכי Settings/PermissionController מורשים לא עוברים למסלול סריקת תוכן רגיל.
- זיהוי VPN דינמי לפי `VpnService` + סריקה תקופתית של VPNים שכבר מותקנים; Magen עצמו מוחרג קשיח.
- ב-Device Owner/Profile Owner VPN מתחרה מושעה/מוסתר, ו-Magen נאכף כ-Always-On VPN עם lockdown והגבלות מערכת.
- Telegram bot/SMS/טלפון שותף לאחריות אינם קיימים; אירועי אבטחה נשלחים בחתימה ל-VPS עם retry queue מקומי.
- משפטי חיזוק מסונכרנים מ-endpoint חתום ב-VPS.
- Telegram: טקסט/תיאורי Accessibility גלויים נשלחים ל-VPS/DeepSeek גם ב-LIGHT; בקשות AI ישנות בתור נזרקות, ומילים מפורשות ידועות נחסמות מקומית מיד.
- מדיה שהיא תמונה/וידאו בלבד אינה מסווגת בפיקסלים בגרסה זו; מסלול ה-AI הנוכחי מבוסס על טקסט/תיאור נגישות.
- onboarding/Device Admin/VPN grace הקשיחים מהגרסאות הקודמות נשמרו.

## 3.0.3

- בורר קטגוריות אפליקציות עוצב מחדש ככרטיס ניהול רגיל.
- PIN רגיל אינו פותח עוד חלון maintenance גלובלי.
- חסימת App Info/Permissions/VPN settings בזמן שההגנה חמושה.
- זיהוי VPN דינמי לפי `VpnService` + החרגה קשיחה של Magen עצמו.
- Device Owner enforcement הורחב ל-Always-On VPN lockdown, VPN/Private DNS, app controls, safe boot, debugging והסרה.
- כל transport ישן של התראות חיצוניות הוסר; אירועים נשלחים ל-VPS.
- משפטי חיזוק מגיעים מ-endpoint חתום ב-VPS.
- Telegram: טקסט/כיתובים גלויים עוברים ל-VPS/DeepSeek; מילים מפורשות ידועות נחסמות מקומית מיד.
- UT1/StevenBlack fallback עודכן; metadata של blocklist הותאם ל-`/v1/blocklist/file`.
- תוקנו installer permissions, Python 3.14 dependencies, low-memory blocklist build ו-swap auto-setup בצד ה-VPS.
