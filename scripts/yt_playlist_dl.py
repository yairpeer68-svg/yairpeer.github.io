#!/usr/bin/env python3
"""הורדת פלייליסט מיוטיוב עם עמידות ל-HTTP 403 (Forbidden).

403 ביוטיוב כמעט תמיד נובע מכתובת וידאו חתומה שפג תוקפה, או שנחתמה
עבור "לקוח" (player client) אחר מזה שמוריד בפועל. לכן הסקריפט מריץ סולם
ניסיונות: מנקה cache, מחליף player_client, מאט את הקצב, מוריד פחות
מקטעים במקביל, ולבסוף משתמש ב-cookies מהדפדפן. בין ניסיון לניסיון נשמר
ארכיון הורדות, כך שסרטונים שכבר ירדו לא יורדים שוב.

שימוש:
    python3 yt_playlist_dl.py "https://www.youtube.com/playlist?list=PLxxxx"
    python3 yt_playlist_dl.py URL -o ~/Music -a -U
    python3 yt_playlist_dl.py URL -c firefox -r 2M

דורש: yt-dlp (חובה), ffmpeg (למיזוג וידאו+אודיו והמרה ל-mp3).
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

try:
    import yt_dlp
    from yt_dlp.utils import DownloadError, parse_bytes
except ImportError:
    sys.exit(
        "yt-dlp לא מותקן. התקנה:\n"
        "  pipx install yt-dlp      (מומלץ)\n"
        "  pip install -U yt-dlp\n"
        "  brew install yt-dlp"
    )

# סדר הלקוחות. tv ו-web_safari הם הכי עמידים ל-403 נכון להיום;
# web מושאר אחרון כי הוא זה שהכי סובל מחתימות שפגות.
CLIENTS = ("tv", "web_safari", "mweb", "ios", "android_vr", "web")

# דפדפנים לניסיון האחרון עם cookies, לפי סדר נפוצות.
COOKIE_BROWSERS = ("chrome", "firefox", "brave", "edge")

_C = {"info": "\033[1;36m", "warn": "\033[1;33m", "err": "\033[1;31m", "off": "\033[0m"}
if not sys.stderr.isatty() or os.environ.get("NO_COLOR"):
    _C = dict.fromkeys(_C, "")


def log(msg: str) -> None:
    print(f"{_C['info']}[{time.strftime('%H:%M:%S')}]{_C['off']} {msg}", file=sys.stderr)


def warn(msg: str) -> None:
    print(f"{_C['warn']}[אזהרה]{_C['off']} {msg}", file=sys.stderr)


def err(msg: str) -> None:
    print(f"{_C['err']}[שגיאה]{_C['off']} {msg}", file=sys.stderr)


def exp_backoff(base: float, cap: float):
    """השהיה מעריכית בין ניסיונות חוזרים: base, 2*base, 4*base... עד cap."""
    return lambda attempt: min(cap, base * (2 ** max(0, attempt - 1)))


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="yt_playlist_dl.py",
        description="הורדת פלייליסט מיוטיוב עם התאוששות אוטומטית מ-403",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "דוגמאות:\n"
            '  %(prog)s "https://www.youtube.com/playlist?list=PLxxxx"\n'
            '  %(prog)s "URL" -o ~/Music -a -U          # mp3 + עדכון yt-dlp\n'
            '  %(prog)s "URL" -c firefox -r 2M          # cookies + האטת קצב\n'
            '  %(prog)s "URL" -i 1-10 -s                # פריטים 1-10 עם כתוביות\n'
        ),
    )
    p.add_argument("url", help="כתובת הפלייליסט (או ערוץ / סרטון בודד)")
    p.add_argument("-o", "--out", default="./downloads", help="תיקיית יעד (ברירת מחדל: ./downloads)")
    p.add_argument("-q", "--quality", type=int, default=1080,
                   help="גובה מקסימלי בפיקסלים (0 = הטוב ביותר; ברירת מחדל: 1080)")
    p.add_argument("-f", "--format", dest="fmt", help="מחרוזת format של yt-dlp; דורסת את -q")
    p.add_argument("-a", "--audio", action="store_true", help="אודיו בלבד (mp3 192k)")
    p.add_argument("-s", "--subs", action="store_true", help="להוריד גם כתוביות (כולל אוטומטיות)")
    p.add_argument("-l", "--sub-langs", default="he,en", help="שפות כתוביות (ברירת מחדל: he,en)")
    p.add_argument("-i", "--items", help="פריטים להורדה, למשל 1-10 או 3,7,12")
    p.add_argument("-c", "--cookies-browser", choices=list(COOKIE_BROWSERS) + ["safari", "opera", "vivaldi", "chromium"],
                   help="לשלוף cookies מהדפדפן (צריך לסגור אותו קודם)")
    p.add_argument("-C", "--cookies-file", help="קובץ cookies בפורמט Netscape")
    p.add_argument("-A", "--archive", help="קובץ ארכיון הורדות (ברירת מחדל: <יעד>/.downloaded.txt)")
    p.add_argument("-r", "--rate", help="הגבלת קצב, למשל 2M — עוזרת מול חסימות")
    p.add_argument("-n", "--concurrent", type=int, default=4,
                   help="מקטעים במקביל בניסיון הראשון (ברירת מחדל: 4)")
    p.add_argument("-U", "--update", action="store_true",
                   help="לעדכן את yt-dlp לפני ההורדה (מומלץ כשמקבלים 403)")
    return p.parse_args(argv)


def self_update() -> None:
    """מעדכן את yt-dlp ומריץ מחדש את התהליך כדי לטעון את הגרסה החדשה.

    גל 403 נובע לא פעם משינוי חתימות בצד יוטיוב, ורוב הגלים האלה נסגרים
    בגרסה חדשה. הדגל ההגנתי מונע לולאת עדכון אינסופית.
    """
    if os.environ.get("_YTDL_UPDATED"):
        return

    log("מעדכן את yt-dlp...")
    for cmd in (
        [sys.executable, "-m", "pip", "install", "-q", "-U", "yt-dlp"],
        ["pipx", "upgrade", "yt-dlp"],
    ):
        if shutil.which(cmd[0]) is None and cmd[0] != sys.executable:
            continue
        try:
            subprocess.run(cmd, check=True, capture_output=True)
            break
        except (subprocess.CalledProcessError, OSError):
            continue
    else:
        warn("העדכון נכשל — ממשיך עם הגרסה המותקנת")
        return

    os.environ["_YTDL_UPDATED"] = "1"
    os.execv(sys.executable, [sys.executable, os.path.abspath(__file__)] + sys.argv[1:])


def build_format(args: argparse.Namespace) -> str:
    if args.fmt:
        return args.fmt
    if args.audio:
        return "bestaudio/best"
    if args.quality <= 0:
        return "bestvideo*+bestaudio/best"
    h = args.quality
    return f"bestvideo[height<={h}]+bestaudio/best[height<={h}]/best"


def build_opts(args: argparse.Namespace, archive: Path, cookies_browser: str | None) -> dict:
    """בונה את מילון האפשרויות הקבוע, בלי מה שמשתנה בין ניסיון לניסיון."""
    opts: dict = {
        "ignoreerrors": True,          # פריט בעייתי לא עוצר את כל הפלייליסט
        "continuedl": True,            # המשך הורדות חלקיות
        "download_archive": str(archive),
        "paths": {"home": str(args.out)},
        "outtmpl": {
            "default": "%(playlist_index)s - %(title).150B [%(id)s].%(ext)s",
            "chapter": "%(title).150B - %(section_number)s %(section_title)s.%(ext)s",
        },
        "restrictfilenames": True,
        "windowsfilenames": True,
        "format": build_format(args),
        "merge_output_format": "mp4",
        "overwrites": False,
        "retries": 20,                 # ניסיונות חוזרים ברמת ההורדה
        "fragment_retries": 50,        # 403 מגיע לרוב על מקטע בודד
        "extractor_retries": 10,
        "file_access_retries": 10,
        "retry_sleep_functions": {     # backoff מעריכי על שגיאות HTTP, כולל 403
            "http": exp_backoff(1, 60),
            "fragment": exp_backoff(1, 30),
        },
        "socket_timeout": 30,
        "source_address": "0.0.0.0",   # כפיית IPv4 — IPv6 של ספקים מסוימים חסום ביוטיוב
        "nopart": True,                # פחות קבצים תקועים כשמפסיקים באמצע
        "postprocessors": [],
    }

    if args.audio:
        opts["writethumbnail"] = True
        opts["postprocessors"] += [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"},
            {"key": "EmbedThumbnail"},
        ]

    if args.subs:
        opts["writesubtitles"] = True
        opts["writeautomaticsub"] = True
        opts["subtitleslangs"] = args.sub_langs.split(",")
        opts["postprocessors"] += [
            {"key": "FFmpegSubtitlesConvertor", "format": "srt"},
            {"key": "FFmpegEmbedSubtitle"},
        ]

    # אחרון בשרשרת כדי שהמטא-דאטה תיכתב על הקובץ הסופי
    opts["postprocessors"].append(
        {"key": "FFmpegMetadata", "add_metadata": True, "add_chapters": True}
    )

    if args.items:
        opts["playlist_items"] = args.items
    if args.rate:
        opts["ratelimit"] = parse_bytes(args.rate)
    if args.cookies_file:
        opts["cookiefile"] = args.cookies_file
    if cookies_browser:
        opts["cookiesfrombrowser"] = (cookies_browser,)

    return opts


def attempt(base_opts: dict, url: str, client: str, frags: int, sleep_req: int) -> bool:
    """ניסיון הורדה אחד עם player client מסוים. מחזיר True אם הצליח."""
    opts = dict(base_opts)
    opts.update({
        "extractor_args": {"youtube": {"player_client": [client]}},
        "concurrent_fragment_downloads": frags,
        "sleep_interval_requests": sleep_req,
        "sleep_interval": 1,
        "max_sleep_interval": 5,
    })

    log(f"ניסיון: client={client} | מקבילות={frags} | sleep={sleep_req}s")
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            return ydl.download([url]) == 0
    except DownloadError as exc:
        warn(f"הניסיון עם client={client} נכשל: {exc}")
        return False


def clear_cache() -> None:
    """חתימות/nsig ישנים ב-cache הם סיבה נפוצה ל-403."""
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True}) as ydl:
            ydl.cache.remove()
    except Exception as exc:  # ניקוי cache הוא נחמד-שיהיה, לא קריטי
        warn(f"ניקוי ה-cache נכשל: {exc}")


def archive_ids(archive: Path) -> set[str]:
    """מזהי הסרטונים שכבר ירדו. שורה בארכיון נראית 'youtube <id>'."""
    if not archive.exists():
        return set()
    ids = set()
    for line in archive.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if parts:
            ids.add(parts[-1])
    return ids


def remaining_count(url: str, items: str | None, archive: Path) -> int:
    """כמה פריטים בפלייליסט עדיין לא בארכיון. -1 אם לא הצלחנו לבדוק."""
    opts = {"extract_flat": True, "quiet": True, "no_warnings": True, "ignoreerrors": True}
    if items:
        opts["playlist_items"] = items
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception:
        return -1
    if not info:
        return -1

    entries = info.get("entries")
    if entries is None:  # סרטון בודד, לא פלייליסט
        entries = [info]
    done = archive_ids(archive)
    return sum(1 for e in entries if e and e.get("id") and e["id"] not in done)


def run(args: argparse.Namespace) -> int:
    out = Path(args.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    archive = Path(args.archive).expanduser() if args.archive else out / ".downloaded.txt"

    if shutil.which("ffmpeg") is None:
        warn("ffmpeg לא נמצא — מיזוג וידאו+אודיו והמרה ל-mp3 לא יעבדו כמו שצריך.")

    log(f"גרסת yt-dlp: {yt_dlp.version.__version__}")
    log(f"פלייליסט: {args.url}")
    log(f"יעד: {out}")
    log(f"ארכיון: {archive}")

    log("מנקה את ה-cache של yt-dlp (סיבה נפוצה ל-403)...")
    clear_cache()

    base_opts = build_opts(args, archive, args.cookies_browser)

    for n, client in enumerate(CLIENTS, start=1):
        # מהניסיון השני מאטים: פחות מקטעים במקביל והשהיה בין בקשות.
        if n == 1:
            frags, sleep_req = max(1, args.concurrent), 0
        elif n == 2:
            frags, sleep_req = 2, 1
        else:
            frags, sleep_req = 1, 3

        if attempt(base_opts, args.url, client, frags, sleep_req):
            log(f"הסתיים. הקבצים ב: {out}")
            return 0

        # שגיאה על סרטון פרטי או מחוק אחד לא אמורה להיחשב כישלון —
        # אם לא נשאר מה להוריד, סיימנו.
        left = remaining_count(args.url, args.items, archive)
        if left == 0:
            log("כל הפריטים הזמינים ירדו.")
            log(f"הסתיים. הקבצים ב: {out}")
            return 0
        if left > 0:
            log(f"נשארו {left} פריטים. מנקה cache ועובר ללקוח הבא...")

        clear_cache()
        time.sleep(5)

    # מוצא אחרון: cookies מהדפדפן, אם המשתמש לא ביקש כאלה מלכתחילה.
    if not args.cookies_browser and not args.cookies_file:
        for browser in COOKIE_BROWSERS:
            warn(f"מנסה שוב עם cookies מ-{browser} (עוזר כשיוטיוב דורש אימות/גיל)")
            opts = build_opts(args, archive, browser)
            if attempt(opts, args.url, "tv", 1, 3):
                log(f"הסתיים. הקבצים ב: {out}")
                return 0

    print(
        "\n"
        "לא הצלחנו להשלים את ההורדה. מה שכדאי לנסות עכשיו, לפי הסדר:\n"
        "\n"
        "  1. -U                    — כמעט כל גל של 403 נפתר בגרסה חדשה של yt-dlp.\n"
        "  2. -c firefox / -c chrome — cookies של חשבון מחובר.\n"
        "     (חשוב: לסגור את הדפדפן לפני, אחרת קובץ ה-cookies נעול.)\n"
        "  3. -r 1M -n 1            — האטה; 403 מופיע גם כשמזהים קצב חשוד.\n"
        "  4. VPN / רשת אחרת        — כתובות IP של ספקי ענן חסומות לעיתים קרובות.\n"
        "  5. -i 1-5                — לחתוך את הפלייליסט לקבוצות קטנות.\n"
        "\n"
        "הארכיון שומר מה שכבר ירד, אז אפשר פשוט להריץ שוב את אותה פקודה\n"
        "וההורדה תמשיך מאיפה שנעצרה.",
        file=sys.stderr,
    )
    return 1


def main() -> int:
    args = parse_args()
    if args.update:
        self_update()
    try:
        return run(args)
    except KeyboardInterrupt:
        err("בוטל על ידי המשתמש. הרצה חוזרת תמשיך מאיפה שנעצרת.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
