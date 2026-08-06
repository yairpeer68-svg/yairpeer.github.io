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
        print("\n\033[32m\033[1m✓ הכל תקין — לא נמצאו שגיאות או באגים.\033[0m\n")
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
