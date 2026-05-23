import os
import asyncio
import time
import shutil
import hashlib
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

# --- הגדרות ---
TOKEN = "8744530925:AAFV0WLWghQXqhwK5_NOn2ctr1cBbignoqo"
MY_ID = 7670817500
MAX_FILE_SIZE_MB = 500
DOWNLOAD_TIMEOUT_SEC = 300
STORAGE_TTL_SEC = 600
MAX_CONCURRENT_DOWNLOADS = 3
MAX_RETRIES = 3
PREVIEW_DURATION_SEC = 30

CUSTOM_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/119.0.0.0 Safari/537.36"
    ),
}

PLATFORM_ICONS = {
    "youtube.com": "▶️", "youtu.be": "▶️",
    "soundcloud.com": "🔊", "instagram.com": "📸",
    "tiktok.com": "🎵", "twitter.com": "🐦",
    "x.com": "🐦", "facebook.com": "👤",
    "vimeo.com": "🎬", "twitch.tv": "🟣",
    "spotify.com": "💚",
}

search_state = {}   # user_id -> {query, index}
url_storage = {}    # url_id -> {url, expires_at}
trim_state = {}     # user_id -> {start, end} | None
download_tasks = {} # user_id -> asyncio.Task

os.makedirs("downloads", exist_ok=True)


# ─── Database ──────────────────────────────────────────────────────────────────

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
            user_id              INTEGER PRIMARY KEY,
            default_bitrate      TEXT    DEFAULT '320',
            default_video_qual   TEXT    DEFAULT '720',
            normalize_audio      INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS bookmarks (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id  INTEGER,
            title    TEXT,
            url      TEXT,
            added_at TEXT
        );
    """)
    conn.commit()
    conn.close()


def _conn():
    return sqlite3.connect("history.db")


def save_history(user_id, title, url, fmt, size_mb=0.0):
    url_hash = hashlib.md5(url.encode()).hexdigest()
    with _conn() as c:
        c.execute(
            "INSERT INTO downloads (user_id,title,url,url_hash,format,file_size_mb,downloaded_at)"
            " VALUES (?,?,?,?,?,?,?)",
            (user_id, title, url, url_hash, fmt, size_mb,
             datetime.now().strftime("%Y-%m-%d %H:%M")),
        )


def check_duplicate(user_id, url):
    url_hash = hashlib.md5(url.encode()).hexdigest()
    with _conn() as c:
        return c.execute(
            "SELECT title, downloaded_at FROM downloads"
            " WHERE user_id=? AND url_hash=? ORDER BY id DESC LIMIT 1",
            (user_id, url_hash),
        ).fetchone()


def get_history(user_id, limit=10):
    with _conn() as c:
        return c.execute(
            "SELECT title, format, file_size_mb, downloaded_at FROM downloads"
            " WHERE user_id=? ORDER BY id DESC LIMIT ?",
            (user_id, limit),
        ).fetchall()


def get_stats(user_id):
    with _conn() as c:
        total = c.execute(
            "SELECT COUNT(*), COALESCE(SUM(file_size_mb),0) FROM downloads WHERE user_id=?",
            (user_id,),
        ).fetchone()
        by_fmt = c.execute(
            "SELECT format, COUNT(*) FROM downloads WHERE user_id=? GROUP BY format",
            (user_id,),
        ).fetchall()
    return total, by_fmt


def get_settings(user_id):
    with _conn() as c:
        row = c.execute(
            "SELECT default_bitrate, default_video_qual, normalize_audio"
            " FROM user_settings WHERE user_id=?",
            (user_id,),
        ).fetchone()
    if not row:
        return {"bitrate": "320", "video_qual": "720", "normalize": 0}
    return {"bitrate": row[0], "video_qual": row[1], "normalize": row[2]}


def set_setting(user_id, key, value):
    with _conn() as c:
        c.execute(
            f"INSERT INTO user_settings (user_id, {key}) VALUES (?,?)"
            f" ON CONFLICT(user_id) DO UPDATE SET {key}=?",
            (user_id, value, value),
        )


def add_bookmark(user_id, title, url):
    with _conn() as c:
        c.execute(
            "INSERT INTO bookmarks (user_id,title,url,added_at) VALUES (?,?,?,?)",
            (user_id, title, url, datetime.now().strftime("%Y-%m-%d %H:%M")),
        )


def get_bookmarks(user_id):
    with _conn() as c:
        return c.execute(
            "SELECT id, title, url FROM bookmarks WHERE user_id=? ORDER BY id DESC LIMIT 10",
            (user_id,),
        ).fetchall()


def delete_bookmark(bookmark_id, user_id):
    with _conn() as c:
        c.execute("DELETE FROM bookmarks WHERE id=? AND user_id=?", (bookmark_id, user_id))


# ─── url_storage TTL ───────────────────────────────────────────────────────────

def store_url(url: str) -> str:
    url_id = str(int(time.time() * 100))
    url_storage[url_id] = {"url": url, "expires_at": time.time() + STORAGE_TTL_SEC}
    return url_id


def get_url(url_id: str):
    entry = url_storage.get(url_id)
    return entry["url"] if entry and entry["expires_at"] > time.time() else None


async def cleanup_storage_loop():
    while True:
        await asyncio.sleep(120)
        now = time.time()
        for k in [k for k, v in list(url_storage.items()) if v["expires_at"] < now]:
            url_storage.pop(k, None)


# ─── עזר ──────────────────────────────────────────────────────────────────────

def clean_title(title: str) -> str:
    for p in [
        r"\(.*?[Vv]ideo.*?\)", r"\[.*?[Vv]ideo.*?\]",
        r"Official", r"Lyric", r"Audio", r"קליפ רשמי", r"רשמי",
    ]:
        title = re.sub(p, "", title, flags=re.IGNORECASE)
    return title.strip()


def fmt_duration(sec) -> str:
    if not sec:
        return "?"
    m, s = divmod(int(sec), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def detect_platform(url: str) -> str:
    for domain, icon in PLATFORM_ICONS.items():
        if domain in url:
            return icon
    return "🌐"


def is_playlist(url: str) -> bool:
    return "playlist" in url or ("list=" in url and "youtube" in url)


def ydl_format(action: str) -> str:
    if any(x in action for x in ("mp3", "preview")):
        return "bestaudio/best"
    m = re.search(r"(\d{3,4})", action)
    if m:
        q = m.group(1)
        return f"bestvideo[height<={q}]+bestaudio/best[height<={q}]/best"
    return "best"


async def run_blocking(func):
    return await asyncio.get_running_loop().run_in_executor(None, func)


async def safe_edit(msg, text, **kw):
    try:
        await msg.edit_text(text, **kw)
    except BadRequest:
        pass


# ─── פקודות ───────────────────────────────────────────────────────────────────

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    await update.message.reply_text(
        "המפלצת מוכנה! 🦾\n\n"
        "📌 שלח שם שיר לחיפוש\n"
        "🔗 שלח לינק (YouTube / TikTok / Instagram / SoundCloud ועוד)\n"
        "✂️ חיתוך: שלח לינק → חתוך → שלח זמנים (01:00-01:30)\n"
        "@bot חיפוש inline בכל צ'אט\n\n"
        "/status   — מצב המערכת\n"
        "/history  — 10 הורדות אחרונות\n"
        "/stats    — סטטיסטיקות\n"
        "/settings — הגדרות ברירת מחדל\n"
        "/bookmarks — סימניות\n"
        "/cancel   — בטל הורדה פעילה"
    )


async def status_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    disk = shutil.disk_usage("/")
    mem = psutil.virtual_memory()
    active = sum(1 for t in download_tasks.values() if not t.done())
    await update.message.reply_text(
        f"📊 **מצב המפלצת:**\n"
        f"💾 דיסק: {disk.free//(2**30)} GB פנוי / {disk.total//(2**30)} GB\n"
        f"🧠 RAM: {mem.percent:.0f}% בשימוש\n"
        f"⬇️ הורדות פעילות: {active}/{MAX_CONCURRENT_DOWNLOADS}",
        parse_mode="Markdown",
    )


async def history_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    rows = get_history(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין היסטוריה עדיין.")
        return
    lines = ["📋 **הורדות אחרונות:**"]
    for title, fmt, size_mb, dt in rows:
        sz = f" ({size_mb:.1f}MB)" if size_mb else ""
        lines.append(f"• [{fmt.upper()}]{sz} {title} — {dt}")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")


async def stats_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    (total_n, total_mb), by_fmt = get_stats(update.effective_user.id)
    if not total_n:
        await update.message.reply_text("אין סטטיסטיקות עדיין.")
        return
    lines = [
        f"📈 **סטטיסטיקות:**",
        f"• סה\"כ: {total_n} הורדות, {total_mb:.1f} MB",
    ]
    for fmt, cnt in by_fmt:
        lines.append(f"  [{fmt.upper()}]: {cnt}")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")


async def settings_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    await _show_settings(update.message.reply_text, update.effective_user.id)


async def _show_settings(send_fn, user_id):
    s = get_settings(user_id)
    norm = "✅" if s["normalize"] else "❌"
    buttons = [
        [InlineKeyboardButton("── 🎵 ברירת מחדל MP3 ──", callback_data="noop")],
        [
            InlineKeyboardButton(f"{'★ ' if s['bitrate']=='128' else ''}128k", callback_data="set_br|128"),
            InlineKeyboardButton(f"{'★ ' if s['bitrate']=='192' else ''}192k", callback_data="set_br|192"),
            InlineKeyboardButton(f"{'★ ' if s['bitrate']=='320' else ''}320k", callback_data="set_br|320"),
        ],
        [InlineKeyboardButton("── 🎬 ברירת מחדל וידאו ──", callback_data="noop")],
        [
            InlineKeyboardButton(f"{'★ ' if s['video_qual']=='480' else ''}480p",  callback_data="set_vq|480"),
            InlineKeyboardButton(f"{'★ ' if s['video_qual']=='720' else ''}720p",  callback_data="set_vq|720"),
            InlineKeyboardButton(f"{'★ ' if s['video_qual']=='1080' else ''}1080p", callback_data="set_vq|1080"),
        ],
        [InlineKeyboardButton(f"{norm} נרמול עוצמה", callback_data="toggle_norm")],
    ]
    await send_fn(
        "⚙️ **הגדרות:**",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )


async def bookmarks_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    rows = get_bookmarks(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין סימניות.")
        return
    lines = ["🔖 **סימניות:**"]
    buttons = []
    for bid, title, url in rows:
        url_id = store_url(url)
        lines.append(f"• {title}")
        buttons.append([
            InlineKeyboardButton(f"⬇️ {title[:30]}", callback_data=f"dl_bm|{url_id}"),
            InlineKeyboardButton("🗑", callback_data=f"del_bm|{bid}"),
        ])
    await update.message.reply_text(
        "\n".join(lines),
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )


async def cancel_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    task = download_tasks.get(update.effective_user.id)
    if task and not task.done():
        task.cancel()
        await update.message.reply_text("⛔ ההורדה בוטלה.")
    else:
        await update.message.reply_text("אין הורדה פעילה לביטול.")


# ─── חיפוש ────────────────────────────────────────────────────────────────────

async def execute_search(msg_obj, context, query_text, index=1, user_id=None):
    try:
        info = await run_blocking(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                f"ytsearch10:{query_text}", download=False
            )
        )
        entries = (info or {}).get("entries", [])
        if not entries or index > len(entries):
            await safe_edit(msg_obj, "❌ אין יותר תוצאות.")
            return

        entry = entries[index - 1]
        url_id = store_url(entry["url"])
        search_state[user_id] = {"query": query_text, "index": index}

        s = get_settings(user_id) if user_id else {"bitrate": "320", "video_qual": "720"}
        title = clean_title(entry.get("title", "ללא שם"))
        dur = fmt_duration(entry.get("duration"))
        channel = entry.get("channel") or entry.get("uploader", "")

        buttons = [
            [
                InlineKeyboardButton(f"🎵 MP3 {s['bitrate']}k", callback_data=f"dl_mp3_{s['bitrate']}|{url_id}"),
                InlineKeyboardButton("🔈 30s preview", callback_data=f"dl_preview|{url_id}"),
            ],
            [
                InlineKeyboardButton("🎬 1080p", callback_data=f"dl_video_1080|{url_id}"),
                InlineKeyboardButton(f"🎬 {s['video_qual']}p ★", callback_data=f"dl_video_{s['video_qual']}|{url_id}"),
                InlineKeyboardButton("🎬 480p",  callback_data=f"dl_video_480|{url_id}"),
            ],
            [
                InlineKeyboardButton("✂️ חתוך",  callback_data=f"ask_trim|{url_id}"),
                InlineKeyboardButton("🔖 שמור",  callback_data=f"bookmark|{url_id}"),
                InlineKeyboardButton("⏭️ הבא",   callback_data=f"next|{url_id}"),
            ],
            [InlineKeyboardButton("❌ סגור", callback_data="cancel_msg")],
        ]
        await safe_edit(
            msg_obj,
            f"🎬 **תוצאה {index}:**\n📌 {title}\n👤 {channel}  ⏱ {dur}",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        if (thumb := entry.get("thumbnail")):
            try:
                await context.bot.send_photo(msg_obj.chat_id, thumb, caption=f"🖼 {title}")
            except Exception:
                pass
    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה בחיפוש: {e}")


# ─── Playlist ─────────────────────────────────────────────────────────────────

async def handle_playlist(msg_obj, context, url, user_id):
    await safe_edit(msg_obj, "📋 טוען playlist...")
    try:
        info = await run_blocking(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                url, download=False
            )
        )
        entries = info.get("entries", []) or []
        total = len(entries)
        title = info.get("title", "Playlist")
        url_id = store_url(url)
        s = get_settings(user_id)
        buttons = [
            [
                InlineKeyboardButton(f"🎵 הכל MP3 {s['bitrate']}k", callback_data=f"dl_plist_mp3_{s['bitrate']}|{url_id}|{total}"),
                InlineKeyboardButton(f"🎬 הכל {s['video_qual']}p",  callback_data=f"dl_plist_video_{s['video_qual']}|{url_id}|{total}"),
            ],
            [InlineKeyboardButton("❌ בטל", callback_data="cancel_msg")],
        ]
        await safe_edit(
            msg_obj,
            f"📋 **{title}**\n🎵 {total} פריטים",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה: {e}")


# ─── הודעות ───────────────────────────────────────────────────────────────────

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    text = update.message.text.strip()
    user_id = update.effective_user.id
    time_match = re.match(r"^(\d{1,2}:\d{2}(?::\d{2})?)-(\d{1,2}:\d{2}(?::\d{2})?)$", text)

    if text.startswith("http"):
        icon = detect_platform(text)
        s = get_settings(user_id)
        url_id = store_url(text)
        trim_state[user_id] = None

        dup = check_duplicate(user_id, text)
        dup_note = f"\n⚠️ _הורדת את זה כבר ב-{dup[1]}_" if dup else ""

        if is_playlist(text):
            msg = await update.message.reply_text(f"{icon} מזהה playlist...")
            await handle_playlist(msg, context, text, user_id)
            return

        buttons = [
            [
                InlineKeyboardButton(f"🎵 MP3 {s['bitrate']}k", callback_data=f"dl_mp3_{s['bitrate']}|{url_id}"),
                InlineKeyboardButton("🔈 30s preview", callback_data=f"dl_preview|{url_id}"),
            ],
            [
                InlineKeyboardButton("🎬 1080p", callback_data=f"dl_video_1080|{url_id}"),
                InlineKeyboardButton(f"🎬 {s['video_qual']}p ★", callback_data=f"dl_video_{s['video_qual']}|{url_id}"),
                InlineKeyboardButton("🎬 480p",  callback_data=f"dl_video_480|{url_id}"),
            ],
            [
                InlineKeyboardButton("✂️ חתוך MP3", callback_data=f"ask_trim|{url_id}"),
                InlineKeyboardButton("🔖 שמור",    callback_data=f"bookmark|{url_id}"),
            ],
        ]
        await update.message.reply_text(
            f"{icon} לינק זוהה! בחר פורמט:{dup_note}",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )

    elif time_match:
        s_t, e_t = time_match.groups()
        trim_state[user_id] = {"start": s_t, "end": e_t}
        await update.message.reply_text(f"✂️ חיתוך: {s_t} → {e_t}\nעכשיו לחץ על כפתור ההורדה.")

    else:
        search_state.pop(user_id, None)
        msg = await update.message.reply_text("🔍 מחפש...")
        await execute_search(msg, context, text, index=1, user_id=user_id)


# ─── Progress hook ─────────────────────────────────────────────────────────────

def make_progress_hook(loop, status_msg):
    last_t = [0.0]

    def hook(d):
        if d["status"] != "downloading":
            return
        now = time.time()
        if now - last_t[0] < 3:
            return
        last_t[0] = now

        total = d.get("total_bytes") or d.get("total_bytes_estimate", 0)
        done = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0
        eta = d.get("eta")

        if total:
            pct = done / total * 100
            bar = "▓" * int(pct / 10) + "░" * (10 - int(pct / 10))
            eta_s = f"  ETA {eta}s" if eta else ""
            txt = f"⬇️ [{bar}] {pct:.0f}%\n🚀 {speed/(1024*1024):.1f} MB/s{eta_s}"
        else:
            txt = f"⬇️ מוריד... {done/(1024*1024):.1f} MB"

        asyncio.run_coroutine_threadsafe(safe_edit(status_msg, txt), loop)

    return hook


# ─── Download core ─────────────────────────────────────────────────────────────

async def _do_download(ydl_opts, url, retries=MAX_RETRIES):
    err = None
    for attempt in range(1, retries + 1):
        try:
            def _run():
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=True)
                    return (info or {}).get("title", "קובץ")
            return await run_blocking(_run)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            err = e
            if attempt < retries:
                await asyncio.sleep(2 ** attempt)
    raise err


async def download_manager(update, context, action, url, status_msg, plist_total=None):
    chat_id = status_msg.chat_id
    user_id = update.effective_user.id
    save_path = f"downloads/{int(time.time())}/"
    os.makedirs(save_path, exist_ok=True)
    loop = asyncio.get_running_loop()

    s = get_settings(user_id)
    trim = trim_state.get(user_id)
    is_preview = "preview" in action
    is_mp3 = "mp3" in action or is_preview
    is_plist = "plist" in action

    br_match = re.search(r"mp3_(\d+)", action)
    bitrate = br_match.group(1) if br_match else s["bitrate"]

    ydl_opts = {
        "outtmpl": f"{save_path}%(playlist_index)s-%(title)s.%(ext)s" if is_plist else f"{save_path}%(title)s.%(ext)s",
        "quiet": True,
        "format": ydl_format(action),
        "http_headers": CUSTOM_HEADERS,
        "progress_hooks": [make_progress_hook(loop, status_msg)],
    }

    if is_preview:
        ydl_opts.update({
            "external_downloader": "ffmpeg",
            "external_downloader_args": {"ffmpeg_i": ["-ss", "0", "-t", str(PREVIEW_DURATION_SEC)]},
        })
    elif trim:
        ydl_opts.update({
            "external_downloader": "ffmpeg",
            "external_downloader_args": {"ffmpeg_i": ["-ss", trim["start"], "-to", trim["end"]]},
        })

    if is_mp3:
        pp = [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": bitrate},
            {"key": "FFmpegMetadata"},
            {"key": "EmbedThumbnail"},
        ]
        if s["normalize"]:
            pp.append({"key": "FFmpegNormalizeAudio"})
        ydl_opts["postprocessors"] = pp
        ydl_opts["writethumbnail"] = True

    if is_plist:
        ydl_opts["noplaylist"] = False
        if plist_total:
            ydl_opts["playlistend"] = plist_total

    title = "קובץ"
    total_size = 0.0
    try:
        semaphore = context.bot_data.setdefault("dl_sem", asyncio.Semaphore(MAX_CONCURRENT_DOWNLOADS))
        async with semaphore:
            async with asyncio.timeout(DOWNLOAD_TIMEOUT_SEC):
                title = await _do_download(ydl_opts, url)

        await safe_edit(status_msg, "📤 שולח...")

        for fname in sorted(os.listdir(save_path)):
            fpath = os.path.join(save_path, fname)
            if not os.path.isfile(fpath):
                continue
            size = os.path.getsize(fpath)
            total_size += size
            if size > MAX_FILE_SIZE_MB * 1024 * 1024:
                await context.bot.send_message(
                    chat_id, f"⚠️ קובץ גדול מדי: {fname} ({size//(1024*1024)}MB)"
                )
                continue
            with open(fpath, "rb") as doc:
                if is_mp3:
                    cap = "🔈 תצוגה מקדימה (30 שניות)" if is_preview else None
                    await context.bot.send_audio(chat_id, doc, title=clean_title(title), caption=cap)
                else:
                    await context.bot.send_video(chat_id, doc)

        fmt = "mp3" if is_mp3 else "video"
        save_history(user_id, clean_title(title), url, fmt, total_size / (1024 * 1024))
        await status_msg.delete()

    except asyncio.CancelledError:
        await context.bot.send_message(chat_id, "⛔ ההורדה בוטלה.")
    except TimeoutError:
        await context.bot.send_message(chat_id, "⏰ Timeout — ההורדה לקחה יותר מדי זמן.")
    except Exception as e:
        await context.bot.send_message(chat_id, f"❌ שגיאה לאחר {MAX_RETRIES} ניסיונות:\n{e}")
    finally:
        shutil.rmtree(save_path, ignore_errors=True)
        trim_state[user_id] = None
        download_tasks.pop(user_id, None)


# ─── Inline mode ──────────────────────────────────────────────────────────────

async def inline_query_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.inline_query.query.strip()
    if len(q) < 2:
        return
    try:
        info = await run_blocking(
            lambda: yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True}).extract_info(
                f"ytsearch5:{q}", download=False
            )
        )
        results = []
        for entry in (info or {}).get("entries", [])[:5]:
            title = clean_title(entry.get("title", ""))
            dur = fmt_duration(entry.get("duration"))
            ch = entry.get("channel") or entry.get("uploader", "")
            results.append(
                InlineQueryResultArticle(
                    id=entry["id"],
                    title=title,
                    description=f"👤 {ch}  ⏱ {dur}",
                    input_message_content=InputTextMessageContent(entry["url"]),
                    thumbnail_url=entry.get("thumbnail"),
                )
            )
        await update.inline_query.answer(results, cache_time=30)
    except Exception:
        pass


# ─── כפתורים ──────────────────────────────────────────────────────────────────

async def button_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    q = update.callback_query
    await q.answer()
    parts = q.data.split("|")
    action, payload = parts[0], parts[1] if len(parts) > 1 else None
    extra = parts[2] if len(parts) > 2 else None
    user_id = update.effective_user.id

    # ── ניווט חיפוש ──
    if action == "next":
        state = search_state.get(user_id)
        if not state:
            await safe_edit(q.message, "❌ פג תוקף החיפוש.")
            return
        await execute_search(q.message, context, state["query"], state["index"] + 1, user_id)

    # ── חיתוך ──
    elif action == "ask_trim":
        await q.message.reply_text(
            "✂️ שלח זמנים:\n`01:00-01:30`  או  `01:00:30-01:02:00`",
            parse_mode="Markdown",
        )

    # ── סימנייה ──
    elif action == "bookmark":
        url = get_url(payload)
        if not url:
            await q.answer("❌ פג תוקף.", show_alert=True)
            return
        # קבל כותרת מהמידע הקיים
        try:
            info = await run_blocking(
                lambda: yt_dlp.YoutubeDL({"quiet": True}).extract_info(url, download=False)
            )
            title = clean_title((info or {}).get("title", url[:40]))
        except Exception:
            title = url[:40]
        add_bookmark(user_id, title, url)
        await q.answer(f"🔖 נשמר: {title[:30]}", show_alert=True)

    elif action == "del_bm":
        delete_bookmark(int(payload), user_id)
        await q.answer("🗑 נמחק.", show_alert=True)
        await safe_edit(q.message, "✅ סימנייה נמחקה.")

    # ── הגדרות ──
    elif action == "noop":
        pass

    elif action == "set_br":
        set_setting(user_id, "default_bitrate", payload)
        await q.answer(f"✅ MP3 ברירת מחדל: {payload}kbps", show_alert=True)

    elif action == "set_vq":
        set_setting(user_id, "default_video_qual", payload)
        await q.answer(f"✅ וידאו ברירת מחדל: {payload}p", show_alert=True)

    elif action == "toggle_norm":
        cur = get_settings(user_id)["normalize"]
        new_val = 0 if cur else 1
        set_setting(user_id, "normalize_audio", new_val)
        await q.answer(f"נרמול: {'✅ פעיל' if new_val else '❌ כבוי'}", show_alert=True)

    # ── סגור ──
    elif action == "cancel_msg":
        await safe_edit(q.message, "✅ בוטל.")

    # ── הורדה (playlist) ──
    elif action.startswith("dl_plist"):
        url = get_url(payload)
        if not url:
            await safe_edit(q.message, "❌ הלינק פג תוקף.")
            return
        if (t := download_tasks.get(user_id)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel לביטול.")
            return
        total = int(extra) if extra else None
        await safe_edit(q.message, f"🚀 מוריד playlist ({total or '?'} פריטים)...")
        task = asyncio.create_task(download_manager(update, context, action, url, q.message, total))
        download_tasks[user_id] = task

    # ── הורדה (סימנייה) ──
    elif action == "dl_bm":
        url = get_url(payload)
        if not url:
            await safe_edit(q.message, "❌ הלינק פג תוקף.")
            return
        if (t := download_tasks.get(user_id)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel לביטול.")
            return
        s = get_settings(user_id)
        await safe_edit(q.message, "🚀 המפלצת עובדת...")
        task = asyncio.create_task(
            download_manager(update, context, f"dl_mp3_{s['bitrate']}", url, q.message)
        )
        download_tasks[user_id] = task

    # ── הורדה (רגיל) ──
    elif action.startswith("dl_"):
        url = get_url(payload)
        if not url:
            await safe_edit(q.message, "❌ הלינק פג תוקף. שלח מחדש.")
            return
        if (t := download_tasks.get(user_id)) and not t.done():
            await q.message.reply_text("⚠️ יש הורדה פעילה. /cancel לביטול.")
            return
        await safe_edit(q.message, "🚀 המפלצת עובדת...")
        task = asyncio.create_task(download_manager(update, context, action, url, q.message))
        download_tasks[user_id] = task


# ─── הפעלה ────────────────────────────────────────────────────────────────────

async def post_init(application):
    asyncio.create_task(cleanup_storage_loop())


if __name__ == "__main__":
    init_db()
    print("🚀 המפלצת בהנעה...")
    app = Application.builder().token(TOKEN).post_init(post_init).build()
    app.add_handler(CommandHandler("start",     start))
    app.add_handler(CommandHandler("status",    status_cmd))
    app.add_handler(CommandHandler("history",   history_cmd))
    app.add_handler(CommandHandler("stats",     stats_cmd))
    app.add_handler(CommandHandler("settings",  settings_cmd))
    app.add_handler(CommandHandler("bookmarks", bookmarks_cmd))
    app.add_handler(CommandHandler("cancel",    cancel_cmd))
    app.add_handler(InlineQueryHandler(inline_query_handler))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    app.add_handler(CallbackQueryHandler(button_handler))
    app.run_polling(drop_pending_updates=True)
