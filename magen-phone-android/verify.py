#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify.py — בודק את כל הקוד של שומר הברית ומדווח על שגיאות ובאגים.

למה זה קיים:
    בנייה מלאה של אנדרואיד לוקחת דקות, וחלק מהתקלות (הפניה למחרוזת שלא
    קיימת, רכיב במניפסט בלי מחלקה, סוגריים לא מאוזנים, מסך הגדרות שנפתח
    בלי הגנה מפני קריסה) מתגלות רק אחרי הבנייה — או גרוע מזה, רק אצל
    המשתמש. הסקריפט הזה תופס אותן בשניות, לפני שמתחילים לבנות.

הרצה:
    python3 verify.py            # מהתיקייה magen-android
    python3 verify.py --strict   # גם אזהרות נחשבות כישלון

קוד יציאה:
    0 — נקי (או רק אזהרות, בלי --strict)
    1 — נמצאו שגיאות
"""

import os
import re
import sys
import glob
import collections
import xml.dom.minidom as minidom

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "app/src/main/java")
TEST = os.path.join(ROOT, "app/src/test/java")
RES = os.path.join(ROOT, "app/src/main/res")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
PKG = "com.magen.family"

errors = []
warnings = []
checks_run = 0


def error(category, message):
    errors.append((category, message))


def warn(category, message):
    warnings.append((category, message))


def rel(path):
    return os.path.relpath(path, ROOT)


# ---------------------------------------------------------------- utilities

def strip_java(src):
    """מסיר מחרוזות והערות, כדי שספירת סוגריים לא תושפע מהם."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def java_files(*roots):
    for root in roots:
        if not os.path.isdir(root):
            continue
        for path, _, files in os.walk(root):
            for f in files:
                if f.endswith(".java"):
                    yield os.path.join(path, f)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ------------------------------------------------------------------ checks

def check_java_syntax():
    """סוגריים מאוזנים — תופס עריכה שנקטעה באמצע."""
    global checks_run
    checks_run += 1
    count = 0
    for path in java_files(SRC, TEST):
        count += 1
        text = strip_java(read(path))
        for open_ch, close_ch, name in (('{', '}', 'סוגריים מסולסלים'),
                                        ('(', ')', 'סוגריים עגולים'),
                                        ('[', ']', 'סוגריים מרובעים')):
            a, b = text.count(open_ch), text.count(close_ch)
            if a != b:
                error("תחביר", f"{rel(path)}: {name} לא מאוזנים ({a} פתיחות, {b} סגירות)")
    return f"{count} קבצי Java"


def check_xml_wellformed():
    global checks_run
    checks_run += 1
    files = glob.glob(os.path.join(RES, "**/*.xml"), recursive=True) + [MANIFEST]
    for f in files:
        try:
            minidom.parse(f)
        except Exception as exc:
            error("XML", f"{rel(f)}: {exc}")
    return f"{len(files)} קבצי XML"


def declared_classes():
    names = set()
    for root in (SRC,):
        for path in java_files(root):
            names.add(os.path.relpath(path, root)[:-5].replace(os.sep, "."))
    return names


def check_manifest_components():
    """כל activity/service/receiver מצביע למחלקה שקיימת."""
    global checks_run
    checks_run += 1
    text = read(MANIFEST)
    classes = declared_classes()
    # activity-alias אינו מחלקה — הוא מצביע ל-targetActivity
    comps = re.findall(
        r'<(activity|service|receiver|provider)\s[^>]*android:name="([^"]+)"', text, re.S)
    for kind, name in comps:
        fq = PKG + name if name.startswith(".") else name
        if fq.startswith(PKG) and fq not in classes:
            error("מניפסט", f"<{kind}> '{name}' מצביע למחלקה {fq} שלא קיימת")

    # יעדי activity-alias חייבים להיות מוצהרים כ-<activity>
    declared_acts = set(re.findall(r'<activity\s[^>]*android:name="([^"]+)"', text, re.S))
    for target in set(re.findall(
            r'<activity-alias[^>]*android:targetActivity="([^"]+)"', text, re.S)):
        if target not in declared_acts:
            error("מניפסט", f"activity-alias מצביע ל-{target} שאינו מוצהר כ-<activity>")
    return f"{len(comps)} רכיבים"


def collect_res(tag):
    found = set()
    for f in glob.glob(os.path.join(RES, "values*/*.xml")):
        found |= set(re.findall(rf'<{tag}[^>]*name="([^"]+)"', read(f)))
    # משאבים שנוצרים בזמן בנייה (resValue ב-build.gradle) לא קיימים ב-XML,
    # ובלי זה היינו מדווחים עליהם כחסרים ומכשילים את הבנייה בטעות.
    gradle = os.path.join(ROOT, "app/build.gradle")
    if os.path.exists(gradle):
        found |= set(re.findall(rf'resValue\s+"{tag}"\s*,\s*"([^"]+)"', read(gradle)))
    return found


def check_resource_refs():
    """כל R.* שמוזכר בקוד קיים בפועל. מתעלם מ-android.R.* (משאבי מערכת)."""
    global checks_run
    checks_run += 1
    defined = {
        "string": collect_res("string"),
        "color": collect_res("color"),
        "array": collect_res("string-array") | collect_res("integer-array") | collect_res("array"),
        "layout": {os.path.basename(f)[:-4] for f in glob.glob(os.path.join(RES, "layout/*.xml"))},
        "drawable": {os.path.basename(f).rsplit('.', 1)[0]
                     for f in glob.glob(os.path.join(RES, "drawable*/*"))},
        "xml": {os.path.basename(f)[:-4] for f in glob.glob(os.path.join(RES, "xml/*.xml"))},
    }
    defined["id"] = set()
    for f in glob.glob(os.path.join(RES, "layout/*.xml")):
        defined["id"] |= set(re.findall(r'android:id="@\+id/(\w+)"', read(f)))

    total = 0
    for path in java_files(SRC):
        text = read(path)
        for kind, names in defined.items():
            # (?<!android\.) מסנן משאבי מערכת כמו android.R.color.transparent
            for name in re.findall(rf'(?<!android\.)\bR\.{kind}\.(\w+)', text):
                total += 1
                if name not in names:
                    error("משאבים", f"{rel(path)}: R.{kind}.{name} לא קיים")

    # הפניות @string מתוך קובצי layout
    for f in glob.glob(os.path.join(RES, "layout/*.xml")):
        for name in re.findall(r'@string/(\w+)', read(f)):
            total += 1
            if name not in defined["string"]:
                error("משאבים", f"{rel(f)}: @string/{name} לא קיים")
    return f"{total} הפניות"


def check_duplicate_strings():
    global checks_run
    checks_run += 1
    total = 0
    for f in glob.glob(os.path.join(RES, "values*/strings.xml")):
        names = re.findall(r'<string[^>]*name="([^"]+)"', read(f))
        total += len(names)
        for name, count in collections.Counter(names).items():
            if count > 1:
                error("מחרוזות", f"{rel(f)}: '{name}' מוגדר {count} פעמים")
    return f"{total} מחרוזות"


def check_translation_parity():
    """אזהרה על מחרוזת בלי תרגום, ושגיאה על ארגומנטי פורמט שלא תואמים."""
    global checks_run
    checks_run += 1
    he_path = os.path.join(RES, "values/strings.xml")
    en_path = os.path.join(RES, "values-en/strings.xml")
    if not (os.path.exists(he_path) and os.path.exists(en_path)):
        return "דולג (אין קובצי תרגום)"

    def entries(path):
        return dict(re.findall(r'<string name="([^"]+)"[^>]*>(.*?)</string>',
                               read(path), re.S))

    he, en = entries(he_path), entries(en_path)
    for name in sorted(set(he) - set(en)):
        warn("תרגום", f"'{name}' קיים בעברית ואין לו תרגום לאנגלית")
    for name in sorted(set(en) - set(he)):
        error("תרגום", f"'{name}' קיים באנגלית אך חסר בעברית (ברירת המחדל)")
    for name in sorted(set(he) & set(en)):
        a = sorted(re.findall(r'%\d\$[sd]', he[name]))
        b = sorted(re.findall(r'%\d\$[sd]', en[name]))
        if a != b:
            error("תרגום", f"'{name}': ארגומנטי פורמט שונים — עברית {a} מול אנגלית {b}")
    return f"{len(he)} עברית / {len(en)} אנגלית"


def check_unguarded_startactivity():
    """
    פתיחת מסך הגדרות מערכת בלי try/catch — הסיבה לקריסה אמיתית שקרתה
    בשלב מנהל המכשיר. מסכי מערכת לא קיימים בכל ROM.
    """
    global checks_run
    checks_run += 1
    system_intent = re.compile(r'Settings\.ACTION_|ACTION_ADD_DEVICE_ADMIN|VpnService\.prepare')
    found = 0
    for path in java_files(SRC):
        lines = read(path).split("\n")
        for i, line in enumerate(lines):
            if "startActivity(" not in line:
                continue
            if line.strip().startswith("//"):
                continue
            window = "\n".join(lines[max(0, i - 12):i + 1])
            if not system_intent.search(window):
                continue    # מסך פנימי של האפליקציה — לא יכול לזרוק
            if "SafeLaunch" in window:
                continue    # כבר מוגן
            if "try {" in window or "try{" in window:
                continue
            found += 1
            warn("קריסה אפשרית",
                 f"{rel(path)}:{i + 1} פותח מסך מערכת בלי try/catch או SafeLaunch")
    return f"נמצאו {found} מקרים לא מוגנים"


def check_foreground_services():
    """
    שירות שקורא startForeground() חייב foregroundServiceType במניפסט
    (אנדרואיד 14+), אחרת המערכת זורקת חריגה ומפילה אותו.

    בודקים רק שירותים שבאמת קוראים startForeground בקוד שלהם — אחרת
    כל שירות רגיל היה מדווח כבעיה בלי סיבה.
    """
    global checks_run
    checks_run += 1
    text = read(MANIFEST)
    blocks = re.findall(r'(<service\s.*?(?:/>|</service>))', text, re.S)
    checked = 0
    for block in blocks:
        match = re.search(r'android:name="([^"]+)"', block)
        if not match:
            continue
        name = match.group(1)
        simple = name.rsplit(".", 1)[-1]
        source = os.path.join(SRC, PKG.replace(".", os.sep), "service", simple + ".java")
        if not os.path.exists(source):
            continue
        if "startForeground(" not in read(source):
            continue          # שירות רגיל — לא נדרש type
        checked += 1
        if "foregroundServiceType" not in block:
            error("שירותים",
                  f"{name} קורא startForeground() אך חסר foregroundServiceType במניפסט "
                  f"(קורס באנדרואיד 14+)")
    return f"{checked} שירותי חזית נבדקו"



def check_permission_engine_regressions():
    """
    Regression checks for bugs that previously broke onboarding on real devices.
    These are intentionally specific: they protect known invariants rather than
    pretending to replace an Android runtime/instrumentation test.
    """
    global checks_run
    checks_run += 1

    onb_path = os.path.join(SRC, PKG.replace('.', os.sep), 'ui', 'OnboardingActivity.java')
    admin_path = os.path.join(SRC, PKG.replace('.', os.sep), 'admin', 'MagenDeviceAdmin.java')
    acc_path = os.path.join(SRC, PKG.replace('.', os.sep), 'service', 'MagenAccessibilityService.java')
    state_path = os.path.join(SRC, PKG.replace('.', os.sep), 'util', 'AccessibilityState.java')
    safe_path = os.path.join(SRC, PKG.replace('.', os.sep), 'util', 'SafeLaunch.java')
    admin_xml = os.path.join(RES, 'xml', 'device_admin.xml')

    onb = read(onb_path)
    admin = read(admin_path)
    acc = read(acc_path)
    state = read(state_path) if os.path.exists(state_path) else ''
    safe = read(safe_path)
    xml = read(admin_xml)

    # Device Admin activation must originate from an Activity and must not be NEW_TASK.
    if 'MagenDeviceAdmin.requestAdmin(this, 2002)' not in onb:
        error('הרשאות', 'Onboarding אינו מפעיל Device Admin דרך Activity/requestCode')
    activation = admin[admin.find('public static void requestAdmin'):admin.find('public static void openAdminSettings')]
    if 'startActivityForResult' not in activation:
        error('הרשאות', 'Device Admin activation חסר startActivityForResult')
    if 'FLAG_ACTIVITY_NEW_TASK' in strip_java(activation):
        error('הרשאות', 'Device Admin activation מכיל FLAG_ACTIVITY_NEW_TASK')
    disabled = admin[admin.find('public void onDisabled'):admin.find('public static boolean lockDeviceNow')]
    if 'requestAdmin(' in strip_java(disabled):
        error('הרשאות', 'DeviceAdminReceiver מנסה לפתוח מחדש UI של הרשאה מה-background')

    # Runtime permissions must force a state refresh when their dialog returns.
    if 'onRequestPermissionsResult' not in onb or 'render();' not in onb[onb.find('onRequestPermissionsResult'):]:
        error('הרשאות', 'Onboarding לא מרענן state אחרי runtime permission result')

    # The VPN consent dialog opened by onboarding must survive self-defense while grace is active.
    vpn_direct = acc[acc.find('// דיאלוג הסכמת VPN'):acc.find('if (MagenConfig.isWhitelisted', acc.find('// דיאלוג הסכמת VPN'))]
    if ('MagenGuard.SCOPE_VPN' not in vpn_direct or 'MagenGuard.allows' not in vpn_direct):
        error('הרשאות', 'VPN consent dialog לא מכבד scope מורשה של VPN')

    # Exact accessibility component matching is the single source of truth.
    if not os.path.exists(state_path) or 'ComponentName' not in state or 'MagenAccessibilityService.class' not in state:
        error('הרשאות', 'חסר AccessibilityState עם התאמת component מדויקת')
    suspicious = []
    for path in java_files(SRC):
        text = read(path)
        if 'ENABLED_ACCESSIBILITY_SERVICES' in text and ('contains(getPackageName())' in text or 'contains(ctx.getPackageName())' in text or 'contains(c.getPackageName())' in text):
            suspicious.append(rel(path))
    if suspicious:
        error('הרשאות', 'נשארו בדיקות Accessibility לפי substring: ' + ', '.join(suspicious))

    # Restricted-settings helper must not be offered as a fake workaround for Device Admin.
    if 'restrictedHelpRelevant = s.title == R.string.onb_perm_accessibility_title' not in onb:
        error('הרשאות', 'כפתור Restricted Settings אינו מוגבל לשלב Accessibility')
    if r'\u202A' not in onb or r'\u202C' not in onb:
        error('הרשאות', 'מונה שלבי onboarding חסר בידוד LTR בתוך UI עברי')

    # SafeLaunch may add NEW_TASK only for non-Activity contexts.
    if 'if (!(ctx instanceof Activity))' not in safe:
        error('הרשאות', 'SafeLaunch לא מבדיל Activity מ-Service/Receiver לפני NEW_TASK')

    # Unused watch-login policy should not be requested.
    if '<watch-login' in xml:
        error('הרשאות', 'device_admin.xml עדיין מבקש watch-login שאינו בשימוש')

    return 'Device Admin / VPN / Accessibility / runtime callbacks'


def check_hardening_regressions():
    """Known security/privacy invariants from real-device testing."""
    global checks_run
    checks_run += 1
    base = os.path.join(SRC, PKG.replace('.', os.sep))
    pin = read(os.path.join(base, 'ui', 'PinActivity.java'))
    acc = read(os.path.join(base, 'service', 'MagenAccessibilityService.java'))
    vpnapps = read(os.path.join(base, 'service', 'AppInstallReceiver.java'))
    remote = read(os.path.join(base, 'server', 'RemoteIntelligenceClient.java'))
    notif = read(os.path.join(base, 'service', 'NotificationHelper.java'))
    manifest = read(MANIFEST)
    gradle = read(os.path.join(ROOT, 'app', 'build.gradle'))

    # A successful PIN check must not globally disable self-defense for 5 minutes.
    # Maintenance is granted only by explicit permission-management flows.
    for line in strip_java(pin).splitlines():
        if 'MagenGuard.grantMaintenance' in line and 'grantMaintenanceOnSuccess' not in line:
            error('הקשחה', 'PinActivity עדיין מעניק maintenance באופן גלובלי אחרי אימות')
            break

    guard = read(os.path.join(base, 'service', 'MagenGuard.java'))
    main = read(os.path.join(base, 'ui', 'MainActivity.java'))
    if 'SCOPE_ACCESSIBILITY' not in guard or 'SCOPE_DEVICE_ADMIN' not in guard or 'SCOPE_VPN' not in guard:
        error('הקשחה', 'MagenGuard חסר scopes נקודתיים למסכי מערכת')
    if 'grantMaintenance(Context ctx, String scope)' not in guard:
        error('הקשחה', 'MagenGuard אינו מחייב scope בעת פתיחת maintenance')
    if 'grantMaintenance(this);' in main:
        error('הקשחה', 'MainActivity עדיין פותח maintenance גלובלי')
    if 'startVpnIfAlreadyPrepared();' not in main or 'REQ_PIN_VPN' not in main or 'SCOPE_VPN' not in main:
        error('VPN', 'MainActivity אינו משתמש בזרימת VPN fail-safe: התחלה שקטה + PIN + scope')
    if 'tv_vpn_warning' not in main:
        error('VPN', 'MainActivity חסר באנר שחזור VPN כשההרשאה נלקחה')

    # Telegram is only a content source; no Telegram bot/API/SMS credentials or transport.
    forbidden = {
        'TelegramNotifier.java': os.path.join(base, 'service', 'TelegramNotifier.java'),
        'GlobalSentences.java': os.path.join(base, 'service', 'GlobalSentences.java'),
    }
    for name, path in forbidden.items():
        if os.path.exists(path): error('פרטיות', f'{name} עדיין קיים')
    if 'android.permission.SEND_SMS' in manifest or 'ALLOW_DIRECT_SMS' in gradle:
        error('פרטיות', 'נשארה הרשאת/יכולת SMS ישירה ב-APK')
    if 'bot_token' in ''.join(read(x) for x in java_files(SRC)) or 'chat_id' in ''.join(read(x) for x in java_files(SRC)):
        error('פרטיות', 'נשארו שדות Telegram bot/chat בצד הטלפון')

    # Visible Telegram text must go asynchronously to the authenticated VPS.
    for pkg in ('org.telegram.messenger', 'org.thunderdog.challegram'):
        if pkg not in acc: error('AI', f'חסרה סריקת Telegram package: {pkg}')
    if 'classifyTextAsync' not in acc or '/v1/intelligence/text' not in remote:
        error('AI', 'Telegram text אינו מחובר למסלול VPS Intelligence האסינכרוני')
    if 'Math.min(1800' not in remote:
        error('AI', 'קטע הטקסט ל-VPS חזר למגבלה קצרה מהנדרש')
    if 'TEXT_SEQ' not in remote or 'newSingleThreadExecutor' not in remote:
        error('AI', 'תור סיווג הטקסט אינו latest-only ועלול להציף DeepSeek בזמן גלילה')
    if 'if (!isBrowser && shouldScanDom' not in acc or 'TELEGRAM_PACKAGES.contains(pkg)' not in acc:
        error('AI', 'סריקת Telegram תלויה שוב ב-useKeywords/מצב סינון במקום לרוץ כשכבת AI עצמאית')

    # Allowed settings screens must not fall through into normal content scanning.
    settings_guard = 'if (isSettingsPackage(pkg) || pkg.contains("vpndialog"))'
    guard_pos = acc.find(settings_guard)
    filter_pos = acc.find('if (!((MagenApp) getApplication()).isFilterEnabled()) return;')
    if guard_pos < 0 or filter_pos < 0 or 'return;' not in acc[guard_pos:filter_pos]:
        error('הקשחה', 'מסך מערכת מורשה עלול ליפול למסלול סינון התוכן הרגיל')

    watch = read(os.path.join(base, 'service', 'ProtectionWatch.java'))
    if 'String activeScope = MagenGuard.activeScope(ctx);' not in watch:
        error('הקשחה', 'ProtectionWatch עדיין משבית את כל בדיקות ההגנה בזמן scope נקודתי')

    # Magen itself declares VpnService; never mistake our own APK/install/update for a competing VPN.
    if 'pkg.equals(ctx.getPackageName())' not in vpnapps:
        error('VPN', 'AppInstallReceiver אינו מוחרג מהחבילה של Magen עצמו')
    if '!pkg.equals(getPackageName())' not in acc:
        error('VPN', 'Accessibility אינו מוחרג במפורש מחסימת ה-VPN של Magen עצמו')

    # Device Owner mode is the OS-enforced path, not only UI interception.
    enterprise = read(os.path.join(base, 'admin', 'EnterpriseProtection.java'))
    for required in ('setAlwaysOnVpnPackage', 'DISALLOW_CONFIG_VPN', 'DISALLOW_APPS_CONTROL',
                     'DISALLOW_SAFE_BOOT', 'DISALLOW_DEBUGGING_FEATURES', 'setUninstallBlocked'):
        if required not in enterprise:
            error('DeviceOwner', f'חסרה אכיפת Device Owner: {required}')

    # App blocking must look like the other settings: one QuickAction card, details collapsed by default.
    layout = read(os.path.join(RES, 'layout', 'activity_main.xml'))
    if 'android:id="@+id/btn_app_blocking_section"' not in layout or 'style="@style/QuickAction"' not in layout:
        error('UI', 'ניהול האפליקציות אינו משולב כ-QuickAction')
    controls_pos = layout.find('android:id="@+id/app_blocking_controls"')
    if controls_pos < 0 or 'android:visibility="gone"' not in layout[controls_pos:controls_pos+500]:
        error('UI', 'פקדי חסימת האפליקציות אינם מקופלים כברירת מחדל')

    # Legacy external notification/accountability transports must stay gone.
    all_main = manifest + ''.join(read(x) for x in java_files(SRC))
    for stale in ('TelegramNotifier', 'GlobalSentences', 'bot_token', 'chat_id', 'android.permission.SEND_SMS'):
        if stale in all_main:
            error('פרטיות', f'נשאר רכיב legacy אסור: {stale}')

    # VPN detection must use the actual VpnService declaration, not brands/package names only.
    if 'VpnService.SERVICE_INTERFACE' not in vpnapps or 'BIND_VPN_SERVICE' not in vpnapps:
        error('VPN', 'זיהוי VPN אינו בודק VpnService/BIND_VPN_SERVICE')
    if 'isVpnCapable(this, pkg)' not in acc:
        error('VPN', 'Accessibility אינו משתמש בזיהוי VPN הדינמי')
    if 'enforceInstalledVpnApps' not in vpnapps:
        error('VPN', 'אין audit תקופתי ל-VPN שכבר מותקן')
    if 'setPackagesSuspended' not in vpnapps:
        error('VPN', 'Device Owner אינו משעה VPN מתחרה ברמת המערכת')

    blocklist = read(os.path.join(base, 'service', 'RemoteBlocklist.java'))
    m = re.search(r'EXPECTED_DOMAINS\s*=\s*([0-9_]+)', blocklist)
    if not m or int(m.group(1).replace('_','')) < 5_000_000:
        error('Blocklist', 'Bloom filter עדיין קטן מהרשימה המרכזית של ~4.75M דומיינים')

    # Alerts leave the device through signed Magen server events only.
    if 'ServerEventReporter.report' not in notif:
        error('התראות', 'NotificationHelper אינו מדווח ל-VPS')
    events = read(os.path.join(base, 'server', 'ServerEventReporter.java'))
    if 'MAX_PENDING' not in events or 'flushPendingAsync' not in events or 'magen_event_queue' not in events:
        error('התראות', 'אירועי VPS אינם נשמרים לתור retry מקומי')

    # v4 encouragement stays server-managed and context-aware; v3 fallback remains only for rolling upgrades.
    encouragement = read(os.path.join(base, 'server', 'ServerEncouragementClient.java'))
    falls = read(os.path.join(base, 'service', 'FallSentences.java'))
    blocked = read(os.path.join(base, 'ui', 'BlockedActivity.java'))
    kill = read(os.path.join(base, 'service', 'MagenKillSwitch.java'))
    for kind in ('TYPE_GENERAL','TYPE_BLOCKED','TYPE_PANIC','TYPE_DAILY','TYPE_MILESTONE'):
        if kind not in falls:
            error('V4', f'חסר סוג משפט שרת: {kind}')
    if 'replaceStructuredFromServer' not in encouragement or 'optJSONArray("items")' not in encouragement:
        error('V4', 'ServerEncouragementClient אינו שומר payload מובנה של v4')
    if 'TYPE_BLOCKED' not in blocked:
        error('V4', 'BlockedActivity אינו מבקש משפט BLOCKED')
    if 'TYPE_PANIC' not in kill:
        error('V4', 'MagenKillSwitch אינו מבקש משפט PANIC')

    return 'PIN maintenance / VPS alerts / Telegram AI / dynamic VPN detection / v4 phrases'


def check_visual_phase2():
    xml = read(os.path.join(RES, 'xml', 'accessibility_service_config.xml'))
    if 'android:canTakeScreenshot="true"' not in xml:
        error('Visual', 'canTakeScreenshot חסר ב-accessibility metadata')
    if 'typeViewScrolled' not in xml:
        error('Visual', 'אין אירוע גלילה למנוע החזותי')

    svc = read(os.path.join(SRC, 'com', 'magen', 'family', 'service', 'MagenAccessibilityService.java'))
    if 'new VisualShieldEngine' not in svc:
        error('Visual', 'VisualShieldEngine אינו מחובר ל-AccessibilityService')
    if 'AccessibilityServiceInfo info = getServiceInfo()' not in svc:
        error('Visual', 'החלפת ServiceInfo עלולה למחוק canTakeScreenshot')
    if 'blockVisual(' not in svc or 'MagenVisualCurtain.show' not in svc:
        error('Visual', 'אין מסלול curtain+block לתוכן חזותי')

    vdir = os.path.join(SRC, 'com', 'magen', 'family', 'visual')
    required = ['VisualPolicy.java','NsfwResult.java','VisualDecision.java',
                'OnDeviceNsfwClassifier.java','VisualShieldEngine.java','MagenVisualCurtain.java',
                'VisualTemporalGuard.java','VisualFrameFingerprint.java','VisualRuntimeState.java']
    for name in required:
        if not os.path.isfile(os.path.join(vdir, name)):
            error('Visual', 'חסר '+name)
    joined = ''
    if os.path.isdir(vdir):
        joined = '\n'.join(read(x) for x in glob.glob(os.path.join(vdir, '*.java')))
    for forbidden in ('MagenApiClient', 'signedPost(', 'HttpURLConnection', 'OkHttpClient'):
        if forbidden in joined:
            error('Visual privacy', 'נמצאה גישת רשת בחבילת visual: '+forbidden)
    if '.compress(' in joined or 'FileOutputStream' in joined:
        error('Visual privacy', 'נמצאה אפשרות לקידוד/שמירת screenshot בחבילת visual')

    policy = read(os.path.join(vdir, 'VisualPolicy.java'))
    if 'visual_upload_images' in policy:
        error('Visual privacy', 'הטלפון לא אמור ליישם העלאת תמונות בכלל')

    builder = read(os.path.join(ROOT, 'BUILD_APK_ON_WINDOWS.ps1'))
    if 'nsfw_mobilenet_v2_140_224.zip' not in builder or 'saved_model.tflite' not in builder:
        error('Visual', 'Windows builder אינו מתקין את מודל ה-NSFW')
    gradle = read(os.path.join(ROOT, 'app', 'build.gradle'))
    if 'com.google.ai.edge.litert:litert:1.4.2' not in gradle:
        error('Visual', 'Google LiteRT 1.4.2 runtime חסר')
    if 'visualModelSha256' not in builder or '-PvisualModelSha256=' not in builder:
        error('Visual', 'Windows builder אינו מקבע SHA-256 של מודל ה-Visual AI')
    if 'VISUAL_MODEL_SHA256.lock' not in builder or 'api.github.com/repos/GantMan/nsfw_model/releases/tags/1.1.0' not in builder:
        error('Visual', 'Supply-chain lock/digest validation חסר למודל החזותי')
    engine = read(os.path.join(vdir, 'VisualShieldEngine.java'))
    if 'VisualFrameFingerprint.dHash' not in engine or 'VisualTemporalGuard' not in engine:
        error('Visual', 'Temporal/dedup hardening אינו מחובר למנוע החזותי')
    if 'pendingScheduled' not in engine or 'schedulePending' not in engine:
        error('Visual', 'latest-only scheduling חסר למנוע החזותי')
    vp = read(os.path.join(vdir, 'VisualPolicy.java'))
    if 'p.getInt("duplicate_hamming_threshold", 0)' not in vp:
        error('Visual', 'STRICT must use exact-only dHash dedupe to avoid skipping small visual changes')
    lic = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'THIRD_PARTY_VISUAL_MODEL_LICENSE.txt')
    if not os.path.isfile(lic):
        error('Visual', 'third-party visual model license notice is missing from APK assets')
    return 'local LiteRT + 3x2 tiles + temporal consensus + dHash + opaque curtain + no image upload'


def check_paired_endpoint():
    """v4.2.1 must be cryptographically and operationally paired to the live VPS."""
    global checks_run
    checks_run += 1
    gradle = read(os.path.join(ROOT, 'app', 'build.gradle'))
    server_cfg = read(os.path.join(SRC, 'com', 'magen', 'family', 'server', 'ServerConfig.java'))
    ca_path = os.path.join(RES, 'raw', 'magen_server_ca.crt')
    ca_text = read(ca_path) if os.path.isfile(ca_path) else ''
    expected_url = 'https://51.20.205.229:8443'
    legacy = 'https://51.21.194.27'
    if expected_url not in gradle:
        error('Pairing', 'ברירת המחדל של MAGEN_SERVER_URL אינה ה-endpoint החי 51.20.205.229:8443')
    # Legacy URL is intentionally allowed only in the migration predicate.
    for path in [os.path.join(ROOT,'PAIRING_INFO.txt'), os.path.join(ROOT,'README.md'), os.path.join(ROOT,'app','build.gradle')]:
        if os.path.isfile(path) and legacy in read(path):
            error('Pairing', f'נשאר endpoint ישן בקובץ {rel(path)}')
    if 'isLegacyPairedUrl' not in server_cfg or '.putBoolean(K_ENROLLED,false)' not in server_cfg:
        error('Pairing', 'חסר migration של SharedPreferences מה-endpoint הישן + fresh enrollment')
    if 'BEGIN CERTIFICATE' not in ca_text or 'Magen Private CA' in ca_text:
        # PEM itself is base64, so CN text is not expected to be visible; this only catches accidental prose files.
        pass
    # Exact DER certificate fingerprint without depending on openssl/cryptography in verify.py.
    try:
        import ssl, hashlib
        der = ssl.PEM_cert_to_DER_cert(ca_text)
        fp = hashlib.sha256(der).hexdigest().lower()
        expected_fp = '75709851104101aca1b78dd2d476570ad5cbaf6ce4a3fe5dc789eb45b6cbc7fa'
        if fp != expected_fp:
            error('Pairing', f'CA SHA-256 mismatch: {fp}')
    except Exception as exc:
        error('Pairing', f'לא ניתן לאמת fingerprint של CA: {exc}')
    return 'VPS 51.20.205.229:8443 + pinned CA + legacy URL migration'

# -------------------------------------------------------------------- main

def main():
    strict = "--strict" in sys.argv

    print("\n\033[1mverify — בדיקת קוד שומר הברית\033[0m")
    print("=" * 52)

    checks = [
        ("תחביר Java", check_java_syntax),
        ("תקינות XML", check_xml_wellformed),
        ("רכיבי מניפסט", check_manifest_components),
        ("הפניות למשאבים", check_resource_refs),
        ("מחרוזות כפולות", check_duplicate_strings),
        ("התאמת תרגום", check_translation_parity),
        ("פתיחת מסכי מערכת", check_unguarded_startactivity),
        ("שירותי חזית", check_foreground_services),
        ("מנוע הרשאות", check_permission_engine_regressions),
        ("הקשחה חדשה", check_hardening_regressions),
        ("Visual Shield", check_visual_phase2),
        ("VPS pairing", check_paired_endpoint),
    ]

    for label, fn in checks:
        before_e, before_w = len(errors), len(warnings)
        try:
            detail = fn()
        except Exception as exc:                       # noqa: BLE001
            error("verify", f"הבדיקה '{label}' נכשלה: {exc}")
            detail = "נכשלה"
        new_e = len(errors) - before_e
        new_w = len(warnings) - before_w
        mark = "\033[31m✗\033[0m" if new_e else ("\033[33m!\033[0m" if new_w else "\033[32m✓\033[0m")
        print(f" {mark} {label:<22} {detail}")

    print("=" * 52)

    if errors:
        print(f"\n\033[31m\033[1mנמצאו {len(errors)} שגיאות:\033[0m")
        for category, message in errors:
            print(f"  \033[31m✗\033[0m [{category}] {message}")

    if warnings:
        print(f"\n\033[33m\033[1m{len(warnings)} אזהרות:\033[0m")
        for category, message in warnings:
            print(f"  \033[33m!\033[0m [{category}] {message}")

    if not errors and not warnings:
        print("\n\033[32m\033[1m✓ כל הבדיקות הסטטיות עברו — זו אינה הוכחה שאין באגים או בעיות runtime.\033[0m\n")
        return 0

    if errors:
        print("\n\033[31mיש לתקן את השגיאות לפני בנייה.\033[0m\n")
        return 1

    if strict:
        print("\n\033[33m--strict: אזהרות נחשבות כישלון.\033[0m\n")
        return 1

    print("\n\033[32m✓ אין שגיאות\033[0m (יש אזהרות — כדאי לבדוק).\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
