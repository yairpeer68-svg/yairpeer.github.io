# Wiring — Magen Phone 3.0.4

הפרויקט כבר מחווט. אין צורך להוסיף ידנית services/receivers ל-Manifest.

רכיבים מרכזיים:
- `MagenVpnService` — VPN מקומי.
- `MagenAccessibilityService` — סינון מסך והגנה עצמית.
- `MagenDeviceAdmin` — Device Admin receiver.
- `EnterpriseProtection` — Device Owner/Profile Owner enforcement.
- `ActivityReporter` — דוח פעילות לשרת Magen.
- `RemoteIntelligenceClient` — דומיין/טקסט ל-VPS.
- `ServerEventReporter` — אירועי אבטחה ל-VPS.
- `ServerEncouragementClient` — משפטי חיזוק חתומים מה-VPS.
- `RemoteBlocklist` — snapshot חתום מה-VPS + fallback ציבורי.

אין SMS, אין בוט Telegram ואין מספר טלפון חיצוני להגדרה.
