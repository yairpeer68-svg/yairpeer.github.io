# START HERE — Magen Phone v4.5.1 Audited HTTPS Inspection

1. ודא שה־VPS זמין על `https://51.20.205.229:8443`.
2. לשרת קיים ששודרג אין צורך להחליף CA או pairing.
3. ל־Fresh VPS עם PKI חדש, ייבא pairing בעזרת `IMPORT_SERVER_PAIRING_ON_WINDOWS.bat`.
4. הרץ `BUILD_APK_ON_WINDOWS.bat` ובנה Release חתום בלבד.
5. שמור גיבוי מאובטח של `.magen-private`; הוא מכיל את מפתח חתימת ה־APK.
6. התקן את קובץ ה־Release שנוצר ובדוק VPN/Accessibility/Visual Shield.
7. במכשיר חדש/מאופס ניתן להריץ `PROVISION_DEVICE_OWNER_ON_WINDOWS.bat` להגנת Always-On VPN + Lockdown חזקה יותר.

Magen נשאר על פורט ציבורי `8443`. פורט 443 אינו נדרש ואינו משתנה.
