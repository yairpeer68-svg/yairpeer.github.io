import os
import asyncio
import time
import shutil
import hashlib
import csv
import io
import subprocess
import yt_dlp
import psutil
import re
import sqlite3
from datetime import datetime
from telegram import (
    Update, InlineKeyboardButton, InlineKeyboardMarkup,
    InlineQueryResultArticle, InputTextMessageContent,
)
from telegram.ext import (
    Application, MessageHandler, CommandHandler,
    CallbackQueryHandler, InlineQueryHandler, filters, ContextTypes,
)
from telegram.error import BadRequest

# ── Config ─────────────────────────────────────────────────────────────────────
TOKEN = "8744530925:AAFV0WLWghQXqhwK5_NOn2ctr1cBbignoqo"
MY_ID = 7670817500
MAX_FILE_MB = 500
DL_TIMEOUT = 300
TTL = 600
MAX_DL = 3
MAX_RETRY = 3
PREVIEW_SEC = 30

CUSTOM_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/119.0.0.0 Safari/537.36"
    ),
}

PLATFORM_ICONS = {
    "youtube.com": "▶️", "youtu.be": "▶️", "soundcloud.com": "🔊",
    "instagram.com": "📸", "tiktok.com": "🎵", "twitter.com": "🐦",
    "x.com": "🐦", "facebook.com": "👤", "vimeo.com": "🎬",
    "twitch.tv": "🟣", "spotify.com": "💚", "deezer.com": "🎶",
}

SPEED_MAP = {"075": 0.75, "100": 1.0, "125": 1.25, "150": 1.5, "200": 2.0}

# ── Global state ────────────────────────────────────────────────────────────────
search_state = {}    # user_id -> {query, index, dur_filter}
url_storage = {}     # url_id -> {url, title, expires_at}
trim_state = {}      # user_id -> {start, end} | None
speed_state = {}     # user_id -> float
tag_state = {}       # user_id -> {artist, album, year} | None
download_tasks = {}  # user_id -> asyncio.Task
schedule_state = {}  # user_id -> url_id (waiting for delay minutes)

os.makedirs("downloads", exist_ok=True)


# ── Database ────────────────────────────────────────────────────────────────────

def init_db():
    conn = sqlite3.connect("history.db")
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS downloads (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id       INTEGER,
            title         TEXT,
            url           TEXT,
            url_hash      TEXT,
            format        TEXT,
            file_size_mb  REAL DEFAULT 0,
            downloaded_at TEXT
        );
        CREATE TABLE IF NOT EXISTS user_settings (
            user_id             INTEGER PRIMARY KEY,
            default_bitrate     TEXT    DEFAULT '320',
            default_video_qual  TEXT    DEFAULT '720',
            default_audio_fmt   TEXT    DEFAULT 'mp3',
            normalize_audio     INTEGER DEFAULT 0,
            forward_chat_id     INTEGER DEFAULT NULL,
            split_minutes       INTEGER DEFAULT NULL
        );
        CREATE TABLE IF NOT EXISTS bookmarks (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id  INTEGER,
            title    TEXT,
            url      TEXT,
            added_at TEXT
        );
    """)
    for sql in [
        "ALTER TABLE user_settings ADD COLUMN default_audio_fmt TEXT DEFAULT 'mp3'",
        "ALTER TABLE user_settings ADD COLUMN forward_chat_id   INTEGER DEFAULT NULL",
        "ALTER TABLE user_settings ADD COLUMN split_minutes     INTEGER DEFAULT NULL",
    ]:
        try:
            conn.execute(sql)
        except sqlite3.OperationalError:
            pass
    conn.commit()
    conn.close()


def _db():
    return sqlite3.connect("history.db")


def save_history(user_id, title, url, fmt, size_mb=0.0):
    url_hash = hashlib.md5(url.encode()).hexdigest()
    with _db() as c:
        c.execute(
            "INSERT INTO downloads (user_id,title,url,url_hash,format,file_size_mb,downloaded_at)"
            " VALUES (?,?,?,?,?,?,?)",
            (user_id, title, url, url_hash, fmt, size_mb,
             datetime.now().strftime("%Y-%m-%d %H:%M")),
        )


def check_dup(user_id, url):
    url_hash = hashlib.md5(url.encode()).hexdigest()
    with _db() as c:
        return c.execute(
            "SELECT title, downloaded_at FROM downloads"
            " WHERE user_id=? AND url_hash=? ORDER BY id DESC LIMIT 1",
            (user_id, url_hash),
        ).fetchone()


def get_history(user_id, limit=10):
    with _db() as c:
        return c.execute(
            "SELECT title, format, file_size_mb, downloaded_at FROM downloads"
            " WHERE user_id=? ORDER BY id DESC LIMIT ?",
            (user_id, limit),
        ).fetchall()


def get_stats(user_id):
    with _db() as c:
        total = c.execute(
            "SELECT COUNT(*), COALESCE(SUM(file_size_mb),0) FROM downloads WHERE user_id=?",
            (user_id,),
        ).fetchone()
        by_fmt = c.execute(
            "SELECT format, COUNT(*) FROM downloads WHERE user_id=? GROUP BY format",
            (user_id,),
        ).fetchall()
    return total, by_fmt


def get_all_for_export(user_id):
    with _db() as c:
        return c.execute(
            "SELECT title, url, format, file_size_mb, downloaded_at"
            " FROM downloads WHERE user_id=? ORDER BY id DESC",
            (user_id,),
        ).fetchall()


def clear_history(user_id):
    with _db() as c:
        c.execute("DELETE FROM downloads WHERE user_id=?", (user_id,))


def get_settings(user_id):
    with _db() as c:
        row = c.execute(
            "SELECT default_bitrate, default_video_qual, default_audio_fmt,"
            " normalize_audio, forward_chat_id, split_minutes"
            " FROM user_settings WHERE user_id=?",
            (user_id,),
        ).fetchone()
    if not row:
        return {"bitrate": "320", "vq": "720", "fmt": "mp3",
                "norm": 0, "fwd": None, "split": None}
    return {"bitrate": row[0], "vq": row[1], "fmt": row[2] or "mp3",
            "norm": row[3], "fwd": row[4], "split": row[5]}


def set_setting(user_id, key, value):
    with _db() as c:
        c.execute(
            f"INSERT INTO user_settings (user_id, {key}) VALUES (?,?)"
            f" ON CONFLICT(user_id) DO UPDATE SET {key}=?",
            (user_id, value, value),
        )


def add_bookmark(user_id, title, url):
    with _db() as c:
        c.execute(
            "INSERT INTO bookmarks (user_id,title,url,added_at) VALUES (?,?,?,?)",
            (user_id, title, url, datetime.now().strftime("%Y-%m-%d %H:%M")),
        )


def get_bookmarks(user_id):
    with _db() as c:
        return c.execute(
            "SELECT id, title, url FROM bookmarks WHERE user_id=? ORDER BY id DESC LIMIT 10",
            (user_id,),
        ).fetchall()


def del_bookmark(bid, user_id):
    with _db() as c:
        c.execute("DELETE FROM bookmarks WHERE id=? AND user_id=?", (bid, user_id))


# ── URL Storage ─────────────────────────────────────────────────────────────────

def store_url(url, title=""):
    url_id = str(int(time.time() * 100))
    url_storage[url_id] = {"url": url, "title": title, "expires_at": time.time() + TTL}
    return url_id


def get_url(url_id):
    e = url_storage.get(url_id)
    return e["url"] if e and e["expires_at"] > time.time() else None


def get_stored_title(url_id):
    e = url_storage.get(url_id)
    return (e or {}).get("title", "")


async def cleanup_ttl():
    while True:
        await asyncio.sleep(120)
        now = time.time()
        for k in [k for k, v in list(url_storage.items()) if v["expires_at"] < now]:
            url_storage.pop(k, None)


# ── Helpers ─────────────────────────────────────────────────────────────────────

def clean_title(t):
    for p in [r"\(.*?[Vv]ideo.*?\)", r"\[.*?[Vv]ideo.*?\]",
              r"Official", r"Lyric", r"Audio", r"קליפ רשמי", r"רשמי"]:
        t = re.sub(p, "", t, flags=re.IGNORECASE)
    return t.strip()


def fmt_dur(sec):
    if not sec:
        return "?"
    m, s = divmod(int(sec), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def detect_icon(url):
    for domain, icon in PLATFORM_ICONS.items():
        if domain in url:
            return icon
    return "🌐"


def is_playlist(url):
    return "playlist" in url or ("list=" in url and "youtube" in url)


def is_spotify(url):
    return "spotify.com" in url


def ydl_fmt_str(action):
    if any(x in action for x in ("mp3", "flac", "ogg", "wav", "aac", "prev")):
        return "bestaudio/best"
    m = re.search(r"(\d{3,4})", action)
    if m:
        q = m.group(1)
        return f"bestvideo[height<={q}]+bestaudio/best[height<={q}]/best"
    return "best"


def dur_range(f):
    return {"short": (0, 300), "medium": (300, 1200), "long": (1200, 999999)}.get(f)


async def run_t(fn):
    return await asyncio.get_running_loop().run_in_executor(None, fn)


async def safe_edit(msg, text, **kw):
    try:
        await msg.edit_text(text, **kw)
    except BadRequest:
        pass


# ── Spotify ──────────────────────────────────────────────────────────────────────

async def spotify_to_query(url):
    try:
        info = await run_t(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                url, download=False
            )
        )
        if info:
            artist = info.get("artist") or info.get("uploader", "")
            title = info.get("title", "")
            return f"{artist} {title}".strip()
    except Exception:
        pass
    return None


# ── FFmpeg utilities ─────────────────────────────────────────────────────────────

def _ffprobe_duration(path):
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", path],
            capture_output=True, text=True, timeout=10,
        )
        return float(r.stdout.strip())
    except Exception:
        return 0.0


def _ffmpeg_split(input_path, chunk_min, out_dir):
    dur = _ffprobe_duration(input_path)
    chunk_sec = chunk_min * 60
    ext = os.path.splitext(input_path)[1]
    parts = []
    for i, start in enumerate(range(0, int(dur), chunk_sec)):
        out = os.path.join(out_dir, f"part_{i+1:03d}{ext}")
        subprocess.run(
            ["ffmpeg", "-v", "quiet", "-i", input_path,
             "-ss", str(start), "-t", str(chunk_sec), "-c", "copy", out],
            timeout=120,
        )
        if os.path.exists(out) and os.path.getsize(out) > 0:
            parts.append(out)
    return parts


def _ffmpeg_speed(input_path, speed, output_path):
    subprocess.run(
        ["ffmpeg", "-v", "quiet", "-i", input_path,
         "-filter:a", f"atempo={speed}", output_path],
        timeout=300,
    )


def _ffmpeg_tag(path, tags):
    tmp = path + ".tmp.mp3"
    args = ["ffmpeg", "-v", "quiet", "-i", path]
    if tags.get("artist"): args += ["-metadata", f"artist={tags['artist']}"]
    if tags.get("album"):  args += ["-metadata", f"album={tags['album']}"]
    if tags.get("year"):   args += ["-metadata", f"date={tags['year']}"]
    args += ["-c", "copy", tmp]
    subprocess.run(args, timeout=30)
    if os.path.exists(tmp):
        os.replace(tmp, path)


async def apply_speed(path, speed):
    if speed == 1.0:
        return path
    base, ext_ = os.path.splitext(path)
    out = f"{base}_x{speed}{ext_}"
    await run_t(lambda: _ffmpeg_speed(path, speed, out))
    return out if os.path.exists(out) else path


# ── Commands ─────────────────────────────────────────────────────────────────────

async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    await update.message.reply_text(
        "המפלצת מוכנה! 🦾\n\n"
        "📌 שם שיר → חיפוש YouTube\n"
        "🔗 לינק → הורדה (YouTube/TikTok/Instagram/SoundCloud/Spotify ועוד)\n"
        "📦 כמה לינקים שורה-שורה → batch\n"
        "✂️ חיתוך: לינק → חתוך → שלח זמנים (01:00-01:30)\n\n"
        "/status · /history · /stats · /settings · /bookmarks\n"
        "/speed · /tags · /sc [שם] · /export · /clear_history · /queue · /cancel"
    )


async def cmd_status(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    disk = shutil.disk_usage("/")
    mem = psutil.virtual_memory()
    active = sum(1 for t in download_tasks.values() if not t.done())
    await update.message.reply_text(
        f"📊 **מצב:**\n"
        f"💾 {disk.free//(2**30)} GB פנוי / {disk.total//(2**30)} GB\n"
        f"🧠 RAM: {mem.percent:.0f}%\n"
        f"⬇️ פעיל: {active}/{MAX_DL}",
        parse_mode="Markdown",
    )


async def cmd_history(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    rows = get_history(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין היסטוריה."); return
    lines = ["📋 **הורדות אחרונות:**"]
    for title, fmt, mb, dt in rows:
        sz = f" ({mb:.1f}MB)" if mb else ""
        lines.append(f"• [{fmt.upper()}]{sz} {title} — {dt}")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")


async def cmd_stats(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    (n, mb), by_fmt = get_stats(update.effective_user.id)
    if not n:
        await update.message.reply_text("אין סטטיסטיקות."); return
    lines = [f"📈 **סטטיסטיקות:**\n• סה\"כ: {n} הורדות, {mb:.1f} MB"]
    for fmt, cnt in by_fmt:
        lines.append(f"  [{fmt.upper()}]: {cnt}")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")


async def cmd_clear_history(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    clear_history(update.effective_user.id)
    await update.message.reply_text("🗑 ההיסטוריה נמחקה.")


async def cmd_export(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    rows = get_all_for_export(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין נתונים."); return
    buf = io.StringIO()
    csv.writer(buf).writerows([["Title", "URL", "Format", "MB", "Date"]] + list(rows))
    buf.seek(0)
    await update.message.reply_document(
        document=io.BytesIO(buf.getvalue().encode()),
        filename=f"downloads_{datetime.now().strftime('%Y%m%d')}.csv",
        caption="📊 היסטוריית הורדות",
    )


async def cmd_queue(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    active = sum(1 for t in download_tasks.values() if not t.done())
    if not active:
        await update.message.reply_text("התור ריק.")
    else:
        await update.message.reply_text(f"⬇️ **הורדות פעילות: {active}**", parse_mode="Markdown")


async def cmd_cancel(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    t = download_tasks.get(update.effective_user.id)
    if t and not t.done():
        t.cancel()
        await update.message.reply_text("⛔ ההורדה בוטלה.")
    else:
        await update.message.reply_text("אין הורדה פעילה.")


async def cmd_speed(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    cur = speed_state.get(update.effective_user.id, 1.0)
    labels = [("075", "🐢 0.75x"), ("100", "▶️ 1x"), ("125", "🐇 1.25x"),
              ("150", "⚡ 1.5x"), ("200", "🚀 2x")]
    buttons = [[
        InlineKeyboardButton(f"{'★' if SPEED_MAP[k]==cur else ''}{lbl}", callback_data=f"spd|{k}")
        for k, lbl in labels
    ]]
    await update.message.reply_text(
        f"⚡ **מהירות:** (נוכחית: {cur}x)",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )


async def cmd_tags(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    uid = update.effective_user.id
    args = update.message.text.split(None, 1)
    if len(args) < 2:
        cur = tag_state.get(uid) or {}
        await update.message.reply_text(
            f"🏷 **תגיות ID3:**\n"
            f"• אמן: {cur.get('artist', '—')}\n"
            f"• אלבום: {cur.get('album', '—')}\n"
            f"• שנה: {cur.get('year', '—')}\n\n"
            f"שימוש: `/tags artist=X album=Y year=2024`\n"
            f"לאיפוס: `/tags clear`",
            parse_mode="Markdown",
        )
        return
    arg = args[1].strip()
    if arg.lower() == "clear":
        tag_state[uid] = None
        await update.message.reply_text("🗑 תגיות אופסו.")
        return
    tags = {m.group(1).lower(): m.group(2)
            for m in re.finditer(r'(artist|album|year)=(\S+)', arg, re.I)}
    if tags:
        tag_state[uid] = tags
        await update.message.reply_text(f"✅ תגיות נשמרו: {tags}")
    else:
        await update.message.reply_text("שימוש: `/tags artist=X album=Y year=Z`", parse_mode="Markdown")


async def cmd_settings(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    await _show_settings(update.message.reply_text, update.effective_user.id)


async def _show_settings(send_fn, uid):
    s = get_settings(uid)
    norm = "✅" if s["norm"] else "❌"
    fwd = str(s["fwd"]) if s["fwd"] else "כבוי"
    spl = f"{s['split']} דק'" if s["split"] else "כבוי"
    mk = lambda val, cur: f"★ {val}" if val == cur else val
    buttons = [
        [InlineKeyboardButton("─── 🎵 MP3 bitrate ───", callback_data="noop")],
        [InlineKeyboardButton(mk("128k", s["bitrate"]+"k"), callback_data="br|128"),
         InlineKeyboardButton(mk("192k", s["bitrate"]+"k"), callback_data="br|192"),
         InlineKeyboardButton(mk("320k", s["bitrate"]+"k"), callback_data="br|320")],
        [InlineKeyboardButton("─── 🎬 איכות וידאו ───", callback_data="noop")],
        [InlineKeyboardButton(mk("480p", s["vq"]+"p"), callback_data="vq|480"),
         InlineKeyboardButton(mk("720p", s["vq"]+"p"), callback_data="vq|720"),
         InlineKeyboardButton(mk("1080p", s["vq"]+"p"), callback_data="vq|1080")],
        [InlineKeyboardButton("─── 🎵 פורמט אודיו ───", callback_data="noop")],
        [InlineKeyboardButton(mk("MP3",  s["fmt"].upper()), callback_data="af|mp3"),
         InlineKeyboardButton(mk("FLAC", s["fmt"].upper()), callback_data="af|flac"),
         InlineKeyboardButton(mk("OGG",  s["fmt"].upper()), callback_data="af|ogg"),
         InlineKeyboardButton(mk("WAV",  s["fmt"].upper()), callback_data="af|wav")],
        [InlineKeyboardButton(f"{norm} נרמול עוצמה", callback_data="tnorm")],
        [InlineKeyboardButton(f"✂️ פיצול אוטו: {spl}", callback_data="ask_split"),
         InlineKeyboardButton(f"📤 העברה: {fwd}",    callback_data="ask_fwd")],
    ]
    await send_fn("⚙️ **הגדרות:**", reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown")


async def cmd_bookmarks(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    rows = get_bookmarks(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין סימניות."); return
    lines = ["🔖 **סימניות:**"]
    buttons = []
    for bid, title, url in rows:
        uid_str = store_url(url, title)
        lines.append(f"• {title}")
        buttons.append([
            InlineKeyboardButton(f"⬇️ {title[:25]}", callback_data=f"dl_bm|{uid_str}"),
            InlineKeyboardButton("🗑", callback_data=f"delbm|{bid}"),
        ])
    await update.message.reply_text(
        "\n".join(lines), reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown"
    )


async def cmd_sc(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    args = update.message.text.split(None, 1)
    if len(args) < 2:
        await update.message.reply_text("שימוש: `/sc שם שיר`", parse_mode="Markdown"); return
    msg = await update.message.reply_text("🔊 מחפש ב-SoundCloud...")
    await _sc_search(msg, context, args[1], 1, update.effective_user.id)


async def _sc_search(msg_obj, context, query, index, user_id):
    try:
        info = await run_t(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                f"scsearch10:{query}", download=False
            )
        )
        entries = (info or {}).get("entries", [])
        if not entries or index > len(entries):
            await safe_edit(msg_obj, "❌ אין יותר תוצאות."); return
        entry = entries[index - 1]
        url_id = store_url(entry["url"], entry.get("title", ""))
        search_state[user_id] = {"query": f"sc:{query}", "index": index}
        s = get_settings(user_id)
        title = clean_title(entry.get("title", ""))
        buttons = [
            [InlineKeyboardButton(f"🎵 MP3 {s['bitrate']}k", callback_data=f"dl_mp3_{s['bitrate']}|{url_id}"),
             InlineKeyboardButton("🔈 30s", callback_data=f"dl_prev|{url_id}")],
            [InlineKeyboardButton("⏭️ הבא", callback_data=f"next|{url_id}"),
             InlineKeyboardButton("❌ סגור", callback_data="canc")],
        ]
        await safe_edit(
            msg_obj,
            f"🔊 **SoundCloud {index}:**\n📌 {title}\n👤 {entry.get('uploader','')}  ⏱ {fmt_dur(entry.get('duration'))}",
            reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown",
        )
    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה: {e}")


# ── Search ───────────────────────────────────────────────────────────────────────

async def do_search(msg_obj, context, query, index=1, user_id=None, dur_filter=None):
    try:
        info = await run_t(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                f"ytsearch20:{query}", download=False
            )
        )
        entries = (info or {}).get("entries", [])
        if dur_filter and (rng := dur_range(dur_filter)):
            entries = [e for e in entries if rng[0] <= (e.get("duration") or 0) <= rng[1]]
        if not entries or index > len(entries):
            await safe_edit(msg_obj, "❌ אין יותר תוצאות."); return

        entry = entries[index - 1]
        url_id = store_url(entry["url"], entry.get("title", ""))
        search_state[user_id] = {"query": query, "index": index, "dur_filter": dur_filter}

        s = get_settings(user_id) if user_id else {"bitrate": "320", "vq": "720"}
        title = clean_title(entry.get("title", "ללא שם"))
        dur = fmt_dur(entry.get("duration"))
        ch = entry.get("channel") or entry.get("uploader", "")

        df_row = [
            InlineKeyboardButton(f"{'★' if dur_filter=='short' else ''}קצר<5m",   callback_data=f"df|short|{url_id}"),
            InlineKeyboardButton(f"{'★' if dur_filter=='medium' else ''}בינוני",  callback_data=f"df|medium|{url_id}"),
            InlineKeyboardButton(f"{'★' if dur_filter=='long' else ''}ארוך>20m",  callback_data=f"df|long|{url_id}"),
            InlineKeyboardButton("🔄 הכל", callback_data=f"df|all|{url_id}"),
        ]
        buttons = [
            [InlineKeyboardButton(f"🎵 MP3 {s['bitrate']}k", callback_data=f"dl_mp3_{s['bitrate']}|{url_id}"),
             InlineKeyboardButton("🔈 30s", callback_data=f"dl_prev|{url_id}")],
            [InlineKeyboardButton("🎬 1080p", callback_data=f"dl_vid_1080|{url_id}"),
             InlineKeyboardButton(f"🎬 {s['vq']}p ★", callback_data=f"dl_vid_{s['vq']}|{url_id}"),
             InlineKeyboardButton("🎬 480p",  callback_data=f"dl_vid_480|{url_id}")],
            [InlineKeyboardButton("💿 FLAC", callback_data=f"dl_flac|{url_id}"),
             InlineKeyboardButton("🖼 Thumb", callback_data=f"dl_thumb|{url_id}"),
             InlineKeyboardButton(f"📝+כתוביות", callback_data=f"dl_vid_{s['vq']}_sub|{url_id}")],
            [InlineKeyboardButton("✂️ חתוך", callback_data=f"ask_trim|{url_id}"),
             InlineKeyboardButton("🔖 שמור", callback_data=f"bkmk|{url_id}"),
             InlineKeyboardButton("⏰ תזמן", callback_data=f"ask_sched|{url_id}"),
             InlineKeyboardButton("⏭️ הבא",  callback_data=f"next|{url_id}")],
            df_row,
            [InlineKeyboardButton("❌ סגור", callback_data="canc")],
        ]
        await safe_edit(
            msg_obj,
            f"🎬 **תוצאה {index}:**\n📌 {title}\n👤 {ch}  ⏱ {dur}",
            reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown",
        )
        if (thumb := entry.get("thumbnail")):
            try:
                await context.bot.send_photo(msg_obj.chat_id, thumb, caption=f"🖼 {title}")
            except Exception:
                pass
    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה בחיפוש: {e}")


# ── Playlist ─────────────────────────────────────────────────────────────────────

async def handle_playlist(msg_obj, context, url, user_id):
    await safe_edit(msg_obj, "📋 טוען playlist...")
    try:
        info = await run_t(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                url, download=False
            )
        )
        entries = (info or {}).get("entries", []) or []
        total = len(entries)
        title = info.get("title", "Playlist")
        url_id = store_url(url, title)
        s = get_settings(user_id)
        buttons = [
            [InlineKeyboardButton(f"🎵 הכל MP3 {s['bitrate']}k", callback_data=f"dl_plist_mp3_{s['bitrate']}|{url_id}|{total}"),
             InlineKeyboardButton(f"🎬 הכל {s['vq']}p",          callback_data=f"dl_plist_vid_{s['vq']}|{url_id}|{total}")],
            [InlineKeyboardButton("❌ בטל", callback_data="canc")],
        ]
        await safe_edit(
            msg_obj, f"📋 **{title}**\n🎵 {total} פריטים",
            reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown",
        )
    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה: {e}")


# ── Message Handler ───────────────────────────────────────────────────────────────

async def handle_msg(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID: return
    text = update.message.text.strip()
    uid = update.effective_user.id

    # Awaiting schedule delay
    if uid in schedule_state:
        url_id = schedule_state.pop(uid)
        try:
            delay = int(text)
            url = get_url(url_id)
            if url:
                await update.message.reply_text(f"⏰ מתוזמן בעוד {delay} דקות.")
                asyncio.create_task(_run_scheduled(update, context, url_id, delay))
            else:
                await update.message.reply_text("❌ הלינק פג תוקף.")
        except ValueError:
            await update.message.reply_text("❌ שלח מספר דקות.")
        return

    # Awaiting split or forward settings
    awaiting = context.user_data.get("awaiting")
    if awaiting == "split":
        context.user_data.pop("awaiting")
        try:
            mins = int(text)
            val = mins if mins > 0 else None
            set_setting(uid, "split_minutes", val)
            await update.message.reply_text(f"✅ פיצול: {f'{mins} דק' if val else 'כבוי'}.")
        except ValueError:
            await update.message.reply_text("❌ שלח מספר דקות.")
        return
    if awaiting == "fwd":
        context.user_data.pop("awaiting")
        try:
            fwd_id = int(text)
            set_setting(uid, "forward_chat_id", fwd_id if fwd_id != 0 else None)
            await update.message.reply_text("✅ Auto-forward עודכן.")
        except ValueError:
            await update.message.reply_text("❌ שלח chat_id תקין (0 לכיבוי).")
        return

    # Time range for trim
    time_match = re.match(r"^(\d{1,2}:\d{2}(?::\d{2})?)-(\d{1,2}:\d{2}(?::\d{2})?)$", text)
    if time_match:
        s_t, e_t = time_match.groups()
        trim_state[uid] = {"start": s_t, "end": e_t}
        await update.message.reply_text(f"✂️ חיתוך: {s_t} → {e_t}\nלחץ על כפתור ההורדה.")
        return

    # Batch URLs (newline-separated)
    lines = text.split("\n")
    urls = [l.strip() for l in lines if l.strip().startswith("http")]
    if len(urls) > 1:
        await _batch_download(update, context, urls, uid)
        return

    # Single URL
    if text.startswith("http"):
        await _handle_url(update, context, text, uid)
        return

    # Text search
    search_state.pop(uid, None)
    msg = await update.message.reply_text("🔍 מחפש...")
    await do_search(msg, context, text, index=1, user_id=uid)


async def _batch_download(update, context, urls, uid):
    header = await update.message.reply_text(f"📦 {len(urls)} לינקים — מתחיל batch...")
    s = get_settings(uid)
    for i, url in enumerate(urls, 1):
        msg = await context.bot.send_message(update.effective_chat.id, f"⬇️ {i}/{len(urls)}: {url[:55]}...")
        await download_manager(update, context, f"dl_mp3_{s['bitrate']}", url, msg)
    await safe_edit(header, f"✅ Batch הסתיים — {len(urls)} קבצים.")


async def _handle_url(update, context, url, uid):
    if is_spotify(url):
        msg = await update.message.reply_text("💚 Spotify — מחפש ב-YouTube...")
        q = await spotify_to_query(url)
        if q:
            await do_search(msg, context, q, index=1, user_id=uid)
        else:
            await safe_edit(msg, "❌ לא הצלחתי לזהות את הטראק.")
        return

    icon = detect_icon(url)
    s = get_settings(uid)
    url_id = store_url(url)
    trim_state[uid] = None

    dup = check_dup(uid, url)
    dup_note = f"\n⚠️ _הורדת כבר ב-{dup[1]}_" if dup else ""

    if is_playlist(url):
        msg = await update.message.reply_text(f"{icon} מזהה playlist...")
        await handle_playlist(msg, context, url, uid)
        return

    buttons = [
        [InlineKeyboardButton(f"🎵 MP3 {s['bitrate']}k", callback_data=f"dl_mp3_{s['bitrate']}|{url_id}"),
         InlineKeyboardButton("🔈 30s preview", callback_data=f"dl_prev|{url_id}")],
        [InlineKeyboardButton("🎬 1080p", callback_data=f"dl_vid_1080|{url_id}"),
         InlineKeyboardButton(f"🎬 {s['vq']}p ★", callback_data=f"dl_vid_{s['vq']}|{url_id}"),
         InlineKeyboardButton("🎬 480p",  callback_data=f"dl_vid_480|{url_id}")],
        [InlineKeyboardButton("💿 FLAC",       callback_data=f"dl_flac|{url_id}"),
         InlineKeyboardButton("🎵 OGG",        callback_data=f"dl_ogg|{url_id}"),
         InlineKeyboardButton("🖼 Thumbnail",  callback_data=f"dl_thumb|{url_id}")],
        [InlineKeyboardButton(f"📝+כתוביות",  callback_data=f"dl_vid_{s['vq']}_sub|{url_id}"),
         InlineKeyboardButton("⏰ תזמן",      callback_data=f"ask_sched|{url_id}")],
        [InlineKeyboardButton("✂️ חתוך", callback_data=f"ask_trim|{url_id}"),
         InlineKeyboardButton("🔖 שמור", callback_data=f"bkmk|{url_id}")],
    ]
    await update.message.reply_text(
        f"{icon} לינק זוהה!{dup_note}\nבחר פורמט:",
        reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown",
    )


async def _run_scheduled(update, context, url_id, delay_min):
    await asyncio.sleep(delay_min * 60)
    url = get_url(url_id)
    if not url:
        await context.bot.send_message(update.effective_chat.id, "❌ הלינק המתוזמן פג תוקף.")
        return
    s = get_settings(update.effective_user.id)
    msg = await context.bot.send_message(update.effective_chat.id, "⏰ הורדה מתוזמנת מתחילה...")
    await download_manager(update, context, f"dl_mp3_{s['bitrate']}", url, msg)


# ── Progress Hook ─────────────────────────────────────────────────────────────────

def make_hook(loop, msg, cur_ref=None, tot_ref=None):
    last_t = [0.0]

    def hook(d):
        if d["status"] != "downloading": return
        now = time.time()
        if now - last_t[0] < 3: return
        last_t[0] = now
        total = d.get("total_bytes") or d.get("total_bytes_estimate", 0)
        done = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0
        eta = d.get("eta")
        prefix = f"[{cur_ref[0]}/{tot_ref[0]}] " if cur_ref and tot_ref else ""
        if total:
            pct = done / total * 100
            bar = "▓" * int(pct / 10) + "░" * (10 - int(pct / 10))
            eta_s = f"  ETA {eta}s" if eta else ""
            txt = f"⬇️ {prefix}[{bar}] {pct:.0f}%\n🚀 {speed/(1024*1024):.1f} MB/s{eta_s}"
        else:
            txt = f"⬇️ {prefix}{done/(1024*1024):.1f} MB"
        asyncio.run_coroutine_threadsafe(safe_edit(msg, txt), loop)

    return hook


# ── Download Core ─────────────────────────────────────────────────────────────────

async def _try_dl(ydl_opts, url, retries=MAX_RETRY):
    err = None
    for attempt in range(1, retries + 1):
        try:
            def _run():
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                    return (info or {}).get("title", "קובץ")
            return await run_t(_run)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            err = e
            if attempt < retries:
                await asyncio.sleep(2 ** attempt)
    raise err


async def download_manager(update, context, action, url, status_msg, plist_total=None):
    chat_id = status_msg.chat_id
    uid = update.effective_user.id
    save_path = f"downloads/{int(time.time())}/"
    os.makedirs(save_path, exist_ok=True)
    loop = asyncio.get_running_loop()

    s = get_settings(uid)
    trim = trim_state.get(uid)
    speed = speed_state.get(uid, 1.0)
    tags = tag_state.get(uid) or {}
    split_min = s.get("split")

    is_prev  = "prev"  in action
    is_thumb = "thumb" in action
    is_sub   = "_sub"  in action
    is_plist = "plist" in action
    is_flac  = "flac"  in action
    is_ogg   = "ogg"   in action
    is_wav   = "wav"   in action
    is_audio = any(x in action for x in ("mp3", "flac", "ogg", "wav", "prev"))

    codec = "flac" if is_flac else "vorbis" if is_ogg else "wav" if is_wav else "mp3"
    ext   = "flac" if is_flac else "ogg"   if is_ogg else "wav" if is_wav else "mp3"
    br_m  = re.search(r"mp3_(\d+)", action)
    bitrate = br_m.group(1) if br_m else s["bitrate"]

    # ── Thumbnail only ──
    if is_thumb:
        await safe_edit(status_msg, "🖼 מוריד thumbnail...")
        try:
            info = await run_t(
                lambda: yt_dlp.YoutubeDL({"quiet": True}).extract_info(url, download=False)
            )
            thumb = (info or {}).get("thumbnail")
            if thumb:
                title = clean_title((info or {}).get("title", ""))
                await context.bot.send_photo(chat_id, thumb, caption=f"🖼 {title}")
                await status_msg.delete()
            else:
                await safe_edit(status_msg, "❌ לא נמצאה thumbnail.")
        except Exception as e:
            await safe_edit(status_msg, f"❌ שגיאה: {e}")
        return

    cur_ref = [0]
    tot_ref = [plist_total or 1]

    ydl_opts = {
        "outtmpl": (f"{save_path}%(playlist_index)s-%(title)s.%(ext)s"
                    if is_plist else f"{save_path}%(title)s.%(ext)s"),
        "quiet": True,
        "format": ydl_fmt_str(action),
        "http_headers": CUSTOM_HEADERS,
        "progress_hooks": [make_hook(loop, status_msg, cur_ref, tot_ref)],
    }

    if is_prev:
        ydl_opts.update({
            "external_downloader": "ffmpeg",
            "external_downloader_args": {"ffmpeg_i": ["-ss", "0", "-t", str(PREVIEW_SEC)]},
        })
    elif trim:
        ydl_opts.update({
            "external_downloader": "ffmpeg",
            "external_downloader_args": {"ffmpeg_i": ["-ss", trim["start"], "-to", trim["end"]]},
        })

    if is_audio:
        pp = [{"key": "FFmpegExtractAudio", "preferredcodec": codec,
               "preferredquality": bitrate if codec == "mp3" else "0"},
              {"key": "FFmpegMetadata"},
              {"key": "EmbedThumbnail"}]
        if s["norm"]:
            ydl_opts.setdefault("postprocessor_args", {})["ffmpegextractaudio"] = ["-af", "loudnorm"]
        ydl_opts["postprocessors"] = pp
        ydl_opts["writethumbnail"] = True

    if is_sub:
        ydl_opts.update({
            "writesubtitles": True,
            "embedsubtitles": True,
            "subtitleslangs": ["he", "en"],
            "postprocessors": [{"key": "FFmpegEmbedSubtitle"}],
        })

    if is_plist:
        ydl_opts["noplaylist"] = False
        if plist_total:
            ydl_opts["playlistend"] = plist_total

    title = "קובץ"
    total_size = 0.0
    try:
        sem = context.bot_data.setdefault("dl_sem", asyncio.Semaphore(MAX_DL))
        async with sem:
            async with asyncio.timeout(DL_TIMEOUT):
                title = await _try_dl(ydl_opts, url)

        await safe_edit(status_msg, "📤 שולח...")

        files = sorted(f for f in os.listdir(save_path) if os.path.isfile(os.path.join(save_path, f)))
        tot_ref[0] = len(files)

        for i, fname in enumerate(files, 1):
            cur_ref[0] = i
            fpath = os.path.join(save_path, fname)
            if not os.path.isfile(fpath): continue

            # Apply speed change
            if speed != 1.0 and is_audio:
                fpath = await apply_speed(fpath, speed)

            # Apply custom ID3 tags
            if tags and is_audio and fpath.endswith(".mp3"):
                await run_t(lambda fp=fpath: _ffmpeg_tag(fp, tags))

            # Auto-split long files
            if split_min and is_audio:
                dur = await run_t(lambda fp=fpath: _ffprobe_duration(fp))
                if dur > split_min * 60:
                    parts = await run_t(lambda fp=fpath: _ffmpeg_split(fp, split_min, save_path))
                    for part in parts:
                        sz = os.path.getsize(part)
                        if sz > MAX_FILE_MB * 1024 * 1024:
                            await context.bot.send_message(chat_id, f"⚠️ חלק גדול מדי: {os.path.basename(part)}")
                            continue
                        with open(part, "rb") as doc:
                            await context.bot.send_audio(
                                chat_id, doc, title=f"{clean_title(title)} [{os.path.basename(part)}]"
                            )
                        if s["fwd"]:
                            with open(part, "rb") as doc:
                                try: await context.bot.send_audio(s["fwd"], doc)
                                except Exception: pass
                    total_size += os.path.getsize(fpath)
                    continue

            size = os.path.getsize(fpath)
            total_size += size
            if size > MAX_FILE_MB * 1024 * 1024:
                await context.bot.send_message(
                    chat_id, f"⚠️ קובץ גדול מדי: {fname} ({size//(1024*1024)}MB)"
                ); continue

            with open(fpath, "rb") as doc:
                if is_audio:
                    cap = "🔈 30 שניות preview" if is_prev else None
                    await context.bot.send_audio(chat_id, doc, title=clean_title(title), caption=cap)
                else:
                    await context.bot.send_video(chat_id, doc)

            if s["fwd"] and not is_prev:
                try:
                    with open(fpath, "rb") as doc:
                        if is_audio: await context.bot.send_audio(s["fwd"], doc, title=clean_title(title))
                        else:        await context.bot.send_video(s["fwd"], doc)
                except Exception: pass

        save_history(uid, clean_title(title), url,
                     ext if is_audio else "video", total_size / (1024 * 1024))
        await status_msg.delete()

    except asyncio.CancelledError:
        await context.bot.send_message(chat_id, "⛔ ההורדה בוטלה.")
    except TimeoutError:
        await context.bot.send_message(chat_id, "⏰ Timeout — לקח יותר מדי זמן.")
    except Exception as e:
        await context.bot.send_message(chat_id, f"❌ שגיאה לאחר {MAX_RETRY} ניסיונות:\n{e}")
    finally:
        shutil.rmtree(save_path, ignore_errors=True)
        trim_state[uid] = None
        download_tasks.pop(uid, None)


# ── Inline Mode ───────────────────────────────────────────────────────────────────

async def inline_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.inline_query.query.strip()
    if len(q) < 2: return
    try:
        info = await run_t(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                f"ytsearch5:{q}", download=False
            )
        )
        results = [
            InlineQueryResultArticle(
                id=e["id"], title=clean_title(e.get("title", "")),
                description=f"👤 {e.get('channel') or e.get('uploader','')}  ⏱ {fmt_dur(e.get('duration'))}",
                input_message_content=InputTextMessageContent(e["url"]),
                thumbnail_url=e.get("thumbnail"),
            )
            for e in (info or {}).get("entries", [])[:5]
        ]
        await update.inline_query.answer(results, cache_time=30)
    except Exception:
        pass


# ── Button Handler ────────────────────────────────────────────────────────────────

async def btn_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    await q.answer()
    parts = q.data.split("|")
    act = parts[0]
    p1 = parts[1] if len(parts) > 1 else None
    p2 = parts[2] if len(parts) > 2 else None
    uid = update.effective_user.id

    # ── Navigation ──
    if act == "next":
        state = search_state.get(uid)
        if not state: await safe_edit(q.message, "❌ פג תוקף החיפוש."); return
        qry = state["query"]
        if qry.startswith("sc:"):
            await _sc_search(q.message, context, qry[3:], state["index"] + 1, uid)
        else:
            await do_search(q.message, context, qry, state["index"] + 1, uid, state.get("dur_filter"))

    # ── Duration filter ──
    elif act == "df":
        state = search_state.get(uid, {})
        new_f = None if p1 == "all" else p1
        await do_search(q.message, context, state.get("query", ""), 1, uid, new_f)

    # ── Trim ──
    elif act == "ask_trim":
        await q.message.reply_text("✂️ שלח זמנים:\n`01:00-01:30` או `01:00:30-01:02:00`", parse_mode="Markdown")

    # ── Schedule ──
    elif act == "ask_sched":
        schedule_state[uid] = p1
        await q.message.reply_text("⏰ שלח מספר דקות לעיכוב:")

    # ── Bookmark save ──
    elif act == "bkmk":
        url = get_url(p1)
        if not url: await q.answer("❌ פג תוקף.", show_alert=True); return
        title = get_stored_title(p1) or url[:40]
        if not title or title == url[:40]:
            try:
                info = await run_t(lambda: yt_dlp.YoutubeDL({"quiet": True}).extract_info(url, download=False))
                title = clean_title((info or {}).get("title", url[:40]))
            except Exception:
                pass
        add_bookmark(uid, title, url)
        await q.answer("🔖 נשמר!", show_alert=True)

    # ── Bookmark delete ──
    elif act == "delbm":
        del_bookmark(int(p1), uid)
        await q.answer("🗑 נמחק.", show_alert=True)
        await safe_edit(q.message, "✅ סימנייה נמחקה.")

    # ── Speed ──
    elif act == "spd":
        speed_state[uid] = SPEED_MAP.get(p1, 1.0)
        await q.answer(f"⚡ מהירות: {speed_state[uid]}x", show_alert=True)

    # ── Settings ──
    elif act == "noop":
        pass
    elif act == "br":
        set_setting(uid, "default_bitrate", p1); await q.answer(f"✅ {p1}kbps", show_alert=True)
    elif act == "vq":
        set_setting(uid, "default_video_qual", p1); await q.answer(f"✅ {p1}p", show_alert=True)
    elif act == "af":
        set_setting(uid, "default_audio_fmt", p1); await q.answer(f"✅ {p1.upper()}", show_alert=True)
    elif act == "tnorm":
        cur = get_settings(uid)["norm"]
        set_setting(uid, "normalize_audio", 0 if cur else 1)
        await q.answer(f"נרמול: {'✅' if not cur else '❌'}", show_alert=True)
    elif act == "ask_split":
        context.user_data["awaiting"] = "split"
        await q.message.reply_text("✂️ שלח מספר דקות לפיצול אוטו (0 לכיבוי):")
    elif act == "ask_fwd":
        context.user_data["awaiting"] = "fwd"
        await q.message.reply_text("📤 שלח chat_id להעברה אוטומטית (0 לכיבוי):")

    # ── Cancel ──
    elif act == "canc":
        await safe_edit(q.message, "✅ בוטל.")

    # ── Playlist download ──
    elif act.startswith("dl_plist"):
        url = get_url(p1)
        if not url: await safe_edit(q.message, "❌ פג תוקף."); return
        if (t := download_tasks.get(uid)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel"); return
        total = int(p2) if p2 else None
        await safe_edit(q.message, f"🚀 מוריד playlist ({total or '?'} פריטים)...")
        task = asyncio.create_task(download_manager(update, context, act, url, q.message, total))
        download_tasks[uid] = task

    # ── Bookmark download ──
    elif act == "dl_bm":
        url = get_url(p1)
        if not url: await safe_edit(q.message, "❌ פג תוקף."); return
        if (t := download_tasks.get(uid)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel"); return
        s = get_settings(uid)
        await safe_edit(q.message, "🚀 המפלצת עובדת...")
        task = asyncio.create_task(
            download_manager(update, context, f"dl_mp3_{s['bitrate']}", url, q.message)
        )
        download_tasks[uid] = task

    # ── Regular download ──
    elif act.startswith("dl_"):
        url = get_url(p1)
        if not url: await safe_edit(q.message, "❌ הלינק פג תוקף."); return
        if (t := download_tasks.get(uid)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel"); return
        await safe_edit(q.message, "🚀 המפלצת עובדת...")
        task = asyncio.create_task(download_manager(update, context, act, url, q.message))
        download_tasks[uid] = task


# ── Main ──────────────────────────────────────────────────────────────────────────

async def post_init(app):
    asyncio.create_task(cleanup_ttl())


if __name__ == "__main__":
    init_db()
    print("🚀 המפלצת בהנעה...")
    app = Application.builder().token(TOKEN).post_init(post_init).build()
    app.add_handler(CommandHandler("start",         cmd_start))
    app.add_handler(CommandHandler("status",        cmd_status))
    app.add_handler(CommandHandler("history",       cmd_history))
    app.add_handler(CommandHandler("stats",         cmd_stats))
    app.add_handler(CommandHandler("clear_history", cmd_clear_history))
    app.add_handler(CommandHandler("export",        cmd_export))
    app.add_handler(CommandHandler("queue",         cmd_queue))
    app.add_handler(CommandHandler("cancel",        cmd_cancel))
    app.add_handler(CommandHandler("speed",         cmd_speed))
    app.add_handler(CommandHandler("tags",          cmd_tags))
    app.add_handler(CommandHandler("settings",      cmd_settings))
    app.add_handler(CommandHandler("bookmarks",     cmd_bookmarks))
    app.add_handler(CommandHandler("sc",            cmd_sc))
    app.add_handler(InlineQueryHandler(inline_handler))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_msg))
    app.add_handler(CallbackQueryHandler(btn_handler))
    app.run_polling(drop_pending_updates=True)
