import os
import asyncio
import time
import shutil
import yt_dlp
import psutil
import re
import sqlite3
from datetime import datetime
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import Application, MessageHandler, CommandHandler, CallbackQueryHandler, filters, ContextTypes
from telegram.error import BadRequest

# --- הגדרות ---
TOKEN = "8744530925:AAFV0WLWghQXqhwK5_NOn2ctr1cBbignoqo"
MY_ID = 7670817500
MAX_FILE_SIZE_MB = 500
DOWNLOAD_TIMEOUT_SEC = 300
STORAGE_TTL_SEC = 600       # url_storage נמחק אחרי 10 דק'
MAX_CONCURRENT_DOWNLOADS = 3

CUSTOM_HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/119.0.0.0 Safari/537.36'
    ),
}

search_state = {}    # user_id -> {query, index}
url_storage = {}     # url_id -> {url, expires_at}
trim_state = {}      # user_id -> {start, end} or None
download_tasks = {}  # user_id -> asyncio.Task

if not os.path.exists("downloads"):
    os.makedirs("downloads")


# --- Database (היסטוריה) ---

def init_db():
    conn = sqlite3.connect("history.db")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS downloads (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id       INTEGER,
            title         TEXT,
            url           TEXT,
            format        TEXT,
            downloaded_at TEXT
        )
    """)
    conn.commit()
    conn.close()


def save_to_history(user_id, title, url, fmt):
    conn = sqlite3.connect("history.db")
    conn.execute(
        "INSERT INTO downloads (user_id, title, url, format, downloaded_at) VALUES (?,?,?,?,?)",
        (user_id, title, url, fmt, datetime.now().strftime("%Y-%m-%d %H:%M")),
    )
    conn.commit()
    conn.close()


def get_history(user_id, limit=10):
    conn = sqlite3.connect("history.db")
    rows = conn.execute(
        "SELECT title, format, downloaded_at FROM downloads "
        "WHERE user_id=? ORDER BY id DESC LIMIT ?",
        (user_id, limit),
    ).fetchall()
    conn.close()
    return rows


# --- ניהול url_storage עם TTL ---

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
        expired = [k for k, v in list(url_storage.items()) if v["expires_at"] < now]
        for k in expired:
            url_storage.pop(k, None)


# --- פונקציות עזר ---

def clean_title(title: str) -> str:
    patterns = [
        r'\(.*?[Vv]ideo.*?\)', r'\[.*?[Vv]ideo.*?\]',
        r'Official', r'Lyric', r'Audio',
        r'קליפ רשמי', r'רשמי',
    ]
    for p in patterns:
        title = re.sub(p, '', title, flags=re.IGNORECASE)
    return title.strip()


def format_duration(seconds) -> str:
    if not seconds:
        return "?"
    m, s = divmod(int(seconds), 60)
    h, m = divmod(m, 60)
    return f"{h:02d}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


async def run_blocking(func):
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, func)


async def safe_edit(msg, text, **kwargs):
    try:
        await msg.edit_text(text, **kwargs)
    except BadRequest:
        pass


def get_format_string(action: str) -> str:
    if "mp3" in action:
        return "bestaudio/best"
    quality = re.search(r"(\d+)", action)
    if quality:
        q = quality.group(1)
        return f"bestvideo[height<={q}]+bestaudio/best[height<={q}]/best"
    return "best"


# --- פקודות ---

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    await update.message.reply_text(
        "המפלצת מוכנה! 🦾\n\n"
        "📌 שלח שם שיר לחיפוש\n"
        "🔗 שלח לינק להורדה ישירה\n"
        "✂️ לחיתוך: שלח לינק → בחר 'חתוך' → שלח זמנים (01:00-01:30)\n\n"
        "פקודות:\n"
        "/status  — מצב המערכת\n"
        "/history — 10 הורדות אחרונות\n"
        "/cancel  — בטל הורדה פעילה"
    )


async def status_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    disk = shutil.disk_usage("/")
    mem = psutil.virtual_memory()
    active = sum(1 for t in download_tasks.values() if not t.done())
    msg = (
        f"📊 **מצב המפלצת:**\n"
        f"💾 דיסק פנוי: {disk.free // (2**30)} GB / {disk.total // (2**30)} GB\n"
        f"🧠 RAM: {mem.percent:.0f}% בשימוש\n"
        f"⬇️ הורדות פעילות: {active}/{MAX_CONCURRENT_DOWNLOADS}"
    )
    await update.message.reply_text(msg, parse_mode="Markdown")


async def history_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    rows = get_history(update.effective_user.id)
    if not rows:
        await update.message.reply_text("אין היסטוריה עדיין.")
        return
    lines = ["📋 **הורדות אחרונות:**"]
    for title, fmt, dt in rows:
        lines.append(f"• [{fmt.upper()}] {title} — {dt}")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")


async def cancel_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    user_id = update.effective_user.id
    task = download_tasks.get(user_id)
    if task and not task.done():
        task.cancel()
        await update.message.reply_text("⛔ ההורדה בוטלה.")
    else:
        await update.message.reply_text("אין הורדה פעילה לביטול.")


# --- חיפוש ---

async def execute_search(msg_obj, context, search_text, index=1, user_id=None):
    ydl_opts = {"quiet": True, "extract_flat": True}
    try:
        info = await run_blocking(
            lambda: yt_dlp.YoutubeDL(ydl_opts).extract_info(
                f"ytsearch10:{search_text}", download=False
            )
        )
        if not info or "entries" not in info or index > len(info["entries"]):
            await safe_edit(msg_obj, "❌ לא מצאתי יותר תוצאות.")
            return

        entry = info["entries"][index - 1]
        url_id = store_url(entry["url"])
        search_state[user_id] = {"query": search_text, "index": index}

        title = clean_title(entry.get("title", "ללא שם"))
        duration = format_duration(entry.get("duration"))
        channel = entry.get("channel") or entry.get("uploader", "")

        text = (
            f"🎬 **תוצאה {index}:**\n"
            f"📌 {title}\n"
            f"👤 {channel}  ⏱ {duration}"
        )
        buttons = [
            [
                InlineKeyboardButton("🎵 MP3 320", callback_data=f"dl_mp3_320|{url_id}"),
                InlineKeyboardButton("🎵 MP3 128", callback_data=f"dl_mp3_128|{url_id}"),
            ],
            [
                InlineKeyboardButton("🎬 1080p", callback_data=f"dl_video_1080|{url_id}"),
                InlineKeyboardButton("🎬 720p",  callback_data=f"dl_video_720|{url_id}"),
                InlineKeyboardButton("🎬 480p",  callback_data=f"dl_video_480|{url_id}"),
            ],
            [
                InlineKeyboardButton("✂️ חתוך MP3", callback_data=f"ask_trim|{url_id}"),
                InlineKeyboardButton("⏭️ הבא",      callback_data=f"next|{url_id}"),
            ],
            [InlineKeyboardButton("❌ סגור", callback_data="cancel")],
        ]
        await safe_edit(msg_obj, text, reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown")

        thumb = entry.get("thumbnail")
        if thumb:
            try:
                await context.bot.send_photo(msg_obj.chat_id, thumb, caption=f"🖼 {title}")
            except Exception:
                pass

    except Exception as e:
        await safe_edit(msg_obj, f"❌ שגיאה בחיפוש: {e}")


# --- ניהול הודעות ---

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if update.effective_user.id != MY_ID:
        return
    text = update.message.text.strip()
    user_id = update.effective_user.id

    # תומך בשני פורמטים: MM:SS-MM:SS וגם HH:MM:SS-HH:MM:SS
    time_match = re.match(
        r'^(\d{1,2}:\d{2}(?::\d{2})?)-(\d{1,2}:\d{2}(?::\d{2})?)$', text
    )

    if text.startswith("http"):
        url_id = store_url(text)
        trim_state[user_id] = None
        buttons = [
            [
                InlineKeyboardButton("🎵 MP3 320kbps", callback_data=f"dl_mp3_320|{url_id}"),
                InlineKeyboardButton("🎵 MP3 128kbps", callback_data=f"dl_mp3_128|{url_id}"),
            ],
            [
                InlineKeyboardButton("🎬 1080p", callback_data=f"dl_video_1080|{url_id}"),
                InlineKeyboardButton("🎬 720p",  callback_data=f"dl_video_720|{url_id}"),
                InlineKeyboardButton("🎬 480p",  callback_data=f"dl_video_480|{url_id}"),
            ],
            [InlineKeyboardButton("✂️ חתוך MP3", callback_data=f"ask_trim|{url_id}")],
        ]
        await update.message.reply_text(
            "✅ לינק זוהה! בחר פורמט:", reply_markup=InlineKeyboardMarkup(buttons)
        )

    elif time_match:
        start_t, end_t = time_match.groups()
        trim_state[user_id] = {"start": start_t, "end": end_t}
        await update.message.reply_text(
            f"✂️ חיתוך הוגדר: {start_t} ← → {end_t}\nעכשיו לחץ על כפתור ההורדה."
        )

    else:
        search_state.pop(user_id, None)  # איפוס אינדקס לחיפוש חדש
        status_msg = await update.message.reply_text("🔍 מחפש...")
        await execute_search(status_msg, context, text, index=1, user_id=user_id)


# --- progress hook ---

def make_progress_hook(loop, status_msg):
    last_update = [0.0]

    def hook(d):
        if d["status"] != "downloading":
            return
        now = time.time()
        if now - last_update[0] < 3:  # throttle — עדכון כל 3 שניות לכל היותר
            return
        last_update[0] = now

        total = d.get("total_bytes") or d.get("total_bytes_estimate", 0)
        downloaded = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0

        if total:
            pct = downloaded / total * 100
            filled = int(pct / 10)
            bar = "▓" * filled + "░" * (10 - filled)
            speed_mb = speed / (1024 * 1024)
            text = f"⬇️ מוריד... [{bar}] {pct:.0f}%\n🚀 {speed_mb:.1f} MB/s"
        else:
            dl_mb = downloaded / (1024 * 1024)
            text = f"⬇️ מוריד... {dl_mb:.1f} MB"

        asyncio.run_coroutine_threadsafe(safe_edit(status_msg, text), loop)

    return hook


# --- הורדה ---

async def download_manager(update, context, action, url, status_msg):
    chat_id = status_msg.chat_id
    user_id = update.effective_user.id
    save_path = f"downloads/{int(time.time())}/"
    os.makedirs(save_path, exist_ok=True)
    loop = asyncio.get_running_loop()

    trim = trim_state.get(user_id)
    bitrate = "320" if "320" in action else "128"

    ydl_opts = {
        "outtmpl": f"{save_path}%(title)s.%(ext)s",
        "quiet": True,
        "format": get_format_string(action),
        "http_headers": CUSTOM_HEADERS,
        "progress_hooks": [make_progress_hook(loop, status_msg)],
    }

    if trim:
        ydl_opts.update({
            "external_downloader": "ffmpeg",
            "external_downloader_args": {
                "ffmpeg_i": ["-ss", trim["start"], "-to", trim["end"]]
            },
        })

    if "mp3" in action:
        ydl_opts["postprocessors"] = [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": bitrate},
            {"key": "FFmpegMetadata"},
            {"key": "EmbedThumbnail"},
        ]
        ydl_opts["writethumbnail"] = True

    title = "קובץ"
    try:
        async with asyncio.timeout(DOWNLOAD_TIMEOUT_SEC):
            # סמאפור: מגביל ל-MAX_CONCURRENT_DOWNLOADS הורדות בו-זמנית
            semaphore = context.bot_data.setdefault("semaphore", asyncio.Semaphore(MAX_CONCURRENT_DOWNLOADS))
            async with semaphore:
                def do_download():
                    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                        info = ydl.extract_info(url, download=True)
                        return info.get("title", "קובץ") if info else "קובץ"

                title = await run_blocking(do_download)

        await safe_edit(status_msg, "📤 שולח...")

        for fname in os.listdir(save_path):
            fpath = os.path.join(save_path, fname)
            if not os.path.isfile(fpath):
                continue
            with open(fpath, "rb") as doc:
                if "mp3" in action:
                    await context.bot.send_audio(chat_id, doc, title=clean_title(title))
                else:
                    await context.bot.send_video(chat_id, doc)

        save_to_history(user_id, clean_title(title), url, "mp3" if "mp3" in action else "video")
        await status_msg.delete()

    except asyncio.CancelledError:
        await context.bot.send_message(chat_id, "⛔ ההורדה בוטלה.")

    except TimeoutError:
        await context.bot.send_message(chat_id, "⏰ Timeout — ההורדה לקחה יותר מדי זמן.")

    except Exception as e:
        await context.bot.send_message(chat_id, f"❌ שגיאה: {e}")

    finally:
        if os.path.exists(save_path):
            shutil.rmtree(save_path)
        trim_state[user_id] = None
        download_tasks.pop(user_id, None)


# --- כפתורים ---

async def button_handler(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    await query.answer()
    parts = query.data.split("|")
    action = parts[0]
    payload = parts[1] if len(parts) > 1 else None
    user_id = update.effective_user.id

    if action == "next":
        state = search_state.get(user_id)
        if not state:
            await safe_edit(query.message, "❌ פג תוקף החיפוש. שלח שם שיר מחדש.")
            return
        await execute_search(
            query.message, context,
            state["query"], index=state["index"] + 1, user_id=user_id
        )

    elif action == "ask_trim":
        await query.message.reply_text(
            "✂️ שלח זמנים בפורמט:\n`01:00-01:30`  או  `01:00:30-01:02:00`",
            parse_mode="Markdown",
        )

    elif action.startswith("dl_"):
        url = get_url(payload)
        if not url:
            await safe_edit(query.message, "❌ הלינק פג תוקף. שלח מחדש.")
            return

        existing = download_tasks.get(user_id)
        if existing and not existing.done():
            await query.message.reply_text("⚠️ יש הורדה פעילה. שלח /cancel לביטול.")
            return

        await safe_edit(query.message, "🚀 המפלצת עובדת...")
        task = asyncio.create_task(
            download_manager(update, context, action, url, query.message)
        )
        download_tasks[user_id] = task

    elif action == "cancel":
        await safe_edit(query.message, "✅ בוטל.")


# --- הפעלה ---

async def post_init(application):
    asyncio.create_task(cleanup_storage_loop())


if __name__ == "__main__":
    init_db()
    print("🚀 המפלצת בהנעה...")
    app = (
        Application.builder()
        .token(TOKEN)
        .post_init(post_init)
        .build()
    )
    app.add_handler(CommandHandler("start",   start))
    app.add_handler(CommandHandler("status",  status_cmd))
    app.add_handler(CommandHandler("history", history_cmd))
    app.add_handler(CommandHandler("cancel",  cancel_cmd))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    app.add_handler(CallbackQueryHandler(button_handler))
    app.run_polling(drop_pending_updates=True)
