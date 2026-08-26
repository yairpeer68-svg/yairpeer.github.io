#!/usr/bin/env bash
#
# yt-playlist-dl.sh — הורדת פלייליסט מיוטיוב עם עמידות ל-HTTP 403 (Forbidden).
#
# הרעיון: 403 ביוטיוב כמעט תמיד נובע מכתובת וידאו חתומה שפג תוקפה או
# שנחתמה עבור "לקוח" (player client) אחר מזה שמוריד בפועל. לכן הסקריפט
# מריץ סולם ניסיונות: מנקה cache, מחליף player_client, מאט את הקצב,
# מוריד מקבילות, ולבסוף משתמש ב-cookies. בין ניסיון לניסיון שומרים
# ארכיון הורדות כך שסרטונים שכבר ירדו לא יורדים שוב.
#
# שימוש:
#   ./yt-playlist-dl.sh <PLAYLIST_URL> [אפשרויות]
#
# הרצה עם -h לרשימת האפשרויות המלאה.

set -Eeuo pipefail

readonly SCRIPT_NAME="${0##*/}"

# ---------- ברירות מחדל ----------
URL=""
OUT_DIR="./downloads"
FORMAT=""                 # ריק => נקבע לפי מצב אודיו/וידאו
MAX_HEIGHT="1080"
AUDIO_ONLY=0
WITH_SUBS=0
SUB_LANGS="he,en"
ITEMS=""                  # למשל 1-10 או 3,7,12
COOKIES_BROWSER=""        # chrome / firefox / brave / edge / safari
COOKIES_FILE=""
ARCHIVE=""                # ברירת מחדל: <OUT_DIR>/.downloaded.txt
CONCURRENT=4              # מקטעים במקביל בניסיון הראשון
RATE_LIMIT=""             # למשל 2M
EXTRA_ARGS=()             # כל מה שאחרי --

# סדר הלקוחות. tv ו-web_safari הם הכי עמידים ל-403 נכון להיום;
# web מושאר אחרון כי הוא זה שהכי סובל מחתימות שפגות.
CLIENTS=(tv web_safari mweb ios android_vr web)

usage() {
  cat <<EOF
$SCRIPT_NAME — הורדת פלייליסט מיוטיוב עם התאוששות אוטומטית מ-403

שימוש:
  $SCRIPT_NAME <PLAYLIST_URL> [אפשרויות] [-- <ארגומנטים נוספים ל-yt-dlp>]

אפשרויות:
  -o DIR         תיקיית יעד (ברירת מחדל: $OUT_DIR)
  -q HEIGHT      גובה מקסימלי בפיקסלים (ברירת מחדל: $MAX_HEIGHT; 0 = הטוב ביותר)
  -f FORMAT      מחרוזת format של yt-dlp; דורסת את -q
  -a             אודיו בלבד (mp3 192k)
  -s             להוריד גם כתוביות (כולל אוטומטיות)
  -l LANGS       שפות כתוביות (ברירת מחדל: $SUB_LANGS)
  -i ITEMS       להוריד רק פריטים מסוימים, למשל 1-10 או 3,7,12
  -c BROWSER     לשלוף cookies מהדפדפן: chrome|firefox|brave|edge|safari
  -C FILE        קובץ cookies בפורמט Netscape
  -A FILE        קובץ ארכיון הורדות (ברירת מחדל: <OUT_DIR>/.downloaded.txt)
  -r RATE        הגבלת קצב, למשל 2M — עוזרת מול חסימות
  -n N           מקטעים במקביל בניסיון הראשון (ברירת מחדל: $CONCURRENT)
  -U             לעדכן את yt-dlp לפני ההורדה (מומלץ כשמקבלים 403)
  -h             העזרה הזו

דוגמאות:
  $SCRIPT_NAME "https://www.youtube.com/playlist?list=PLxxxx"
  $SCRIPT_NAME "https://youtube.com/playlist?list=PLxxxx" -o ~/Music -a -U
  $SCRIPT_NAME "https://youtube.com/playlist?list=PLxxxx" -c firefox -r 2M
  $SCRIPT_NAME "https://youtube.com/playlist?list=PLxxxx" -- --write-thumbnail
EOF
}

log()  { printf '\033[1;36m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
warn() { printf '\033[1;33m[אזהרה]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[שגיאה]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- פענוח ארגומנטים ----------
[[ $# -eq 0 ]] && { usage; exit 1; }

DO_UPDATE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -o) OUT_DIR="$2"; shift 2 ;;
    -q) MAX_HEIGHT="$2"; shift 2 ;;
    -f) FORMAT="$2"; shift 2 ;;
    -a) AUDIO_ONLY=1; shift ;;
    -s) WITH_SUBS=1; shift ;;
    -l) SUB_LANGS="$2"; shift 2 ;;
    -i) ITEMS="$2"; shift 2 ;;
    -c) COOKIES_BROWSER="$2"; shift 2 ;;
    -C) COOKIES_FILE="$2"; shift 2 ;;
    -A) ARCHIVE="$2"; shift 2 ;;
    -r) RATE_LIMIT="$2"; shift 2 ;;
    -n) CONCURRENT="$2"; shift 2 ;;
    -U) DO_UPDATE=1; shift ;;
    --) shift; EXTRA_ARGS+=("$@"); break ;;
    -*) die "אפשרות לא מוכרת: $1 (נסה -h)" ;;
    *)  [[ -n "$URL" ]] && die "כתובת אחת בלבד"; URL="$1"; shift ;;
  esac
done

[[ -n "$URL" ]] || die "חסרה כתובת פלייליסט"

# ---------- בדיקות סביבה ----------
command -v yt-dlp >/dev/null 2>&1 || die "yt-dlp לא מותקן. התקנה:
  pipx install yt-dlp      (מומלץ)
  pip install -U yt-dlp
  brew install yt-dlp"

if ! command -v ffmpeg >/dev/null 2>&1; then
  warn "ffmpeg לא נמצא — מיזוג וידאו+אודיו והמרה ל-mp3 לא יעבדו כמו שצריך."
fi

if [[ $DO_UPDATE -eq 1 ]]; then
  log "מעדכן yt-dlp..."
  # 403 נגרם לא פעם משינוי חתימות בצד יוטיוב; גרסה עדכנית פותרת את רובם.
  yt-dlp -U || pipx upgrade yt-dlp 2>/dev/null || pip install -U yt-dlp || \
    warn "העדכון נכשל — ממשיך עם הגרסה המותקנת"
fi

log "גרסת yt-dlp: $(yt-dlp --version)"

mkdir -p "$OUT_DIR"
[[ -n "$ARCHIVE" ]] || ARCHIVE="$OUT_DIR/.downloaded.txt"

# ---------- בניית פורמט ----------
if [[ -z "$FORMAT" ]]; then
  if [[ $AUDIO_ONLY -eq 1 ]]; then
    FORMAT="bestaudio/best"
  elif [[ "$MAX_HEIGHT" == "0" ]]; then
    FORMAT="bestvideo*+bestaudio/best"
  else
    FORMAT="bestvideo[height<=${MAX_HEIGHT}]+bestaudio/best[height<=${MAX_HEIGHT}]/best"
  fi
fi

# ---------- ארגומנטים קבועים ----------
build_base_args() {
  BASE_ARGS=(
    --ignore-errors                 # פריט בעייתי לא עוצר את כל הפלייליסט
    --no-abort-on-error
    --continue                      # המשך הורדות חלקיות
    --download-archive "$ARCHIVE"   # לא להוריד שוב מה שכבר ירד
    --paths "$OUT_DIR"
    --output "%(playlist_index)s - %(title).150B [%(id)s].%(ext)s"
    --output "chapter:%(title).150B - %(section_number)s %(section_title)s.%(ext)s"
    --restrict-filenames
    --windows-filenames
    --format "$FORMAT"
    --merge-output-format mp4
    --embed-metadata
    --embed-chapters
    --no-overwrites
    --retries 20                    # ניסיונות חוזרים ברמת ההורדה
    --fragment-retries 50            # 403 מגיע לרוב על מקטע בודד
    --extractor-retries 10
    --file-access-retries 10
    --retry-sleep "http:exp=1:60"    # backoff מעריכי על שגיאות HTTP (כולל 403)
    --retry-sleep "fragment:exp=1:30"
    --socket-timeout 30
    --force-ipv4                     # IPv6 של ספקים מסוימים חסום/מוגבל ביוטיוב
    --no-part                        # פחות קבצים תקועים כשמפסיקים באמצע
    --progress
    --console-title
  )

  if [[ $AUDIO_ONLY -eq 1 ]]; then
    BASE_ARGS+=( --extract-audio --audio-format mp3 --audio-quality 192K --embed-thumbnail )
  fi

  if [[ $WITH_SUBS -eq 1 ]]; then
    BASE_ARGS+=( --write-subs --write-auto-subs --sub-langs "$SUB_LANGS" --embed-subs --convert-subs srt )
  fi

  if [[ -n "$ITEMS" ]];           then BASE_ARGS+=( --playlist-items "$ITEMS" ); fi
  if [[ -n "$RATE_LIMIT" ]];      then BASE_ARGS+=( --limit-rate "$RATE_LIMIT" ); fi
  if [[ -n "$COOKIES_FILE" ]];    then BASE_ARGS+=( --cookies "$COOKIES_FILE" ); fi
  if [[ -n "$COOKIES_BROWSER" ]]; then BASE_ARGS+=( --cookies-from-browser "$COOKIES_BROWSER" ); fi

  return 0
}

# ---------- ניסיון בודד ----------
# $1 = player client, $2 = מקטעים במקביל, $3 = השהיה בין בקשות (שניות)
attempt() {
  local client="$1" frags="$2" sleep_req="$3"
  local -a args
  args=( "${BASE_ARGS[@]}" )
  args+=(
    --extractor-args "youtube:player_client=${client}"
    --concurrent-fragments "$frags"
    --sleep-requests "$sleep_req"
    --sleep-interval 1
    --max-sleep-interval 5
  )
  args+=( ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} )

  log "ניסיון: client=${client} | מקבילות=${frags} | sleep=${sleep_req}s"
  yt-dlp "${args[@]}" "$URL"
}

# האם נשארו פריטים שלא ירדו? משווים בין מספר הפריטים בפלייליסט לארכיון.
remaining_count() {
  local total downloaded
  local -a sel=()
  if [[ -n "$ITEMS" ]]; then sel=( --playlist-items "$ITEMS" ); fi
  total="$(yt-dlp --flat-playlist --print id --ignore-errors \
            "${sel[@]+"${sel[@]}"}" "$URL" 2>/dev/null | sed '/^$/d' | wc -l | tr -d ' ')"
  downloaded="$(sed '/^$/d' "$ARCHIVE" 2>/dev/null | wc -l | tr -d ' ')"
  echo "$(( ${total:-0} - ${downloaded:-0} ))"
}

# ---------- הרצה ----------
build_base_args

log "פלייליסט: $URL"
log "יעד: $OUT_DIR"
log "ארכיון: $ARCHIVE"

# ניקוי cache: חתימות/nsig ישנים ב-cache הם גורם 403 קלאסי.
log "מנקה את ה-cache של yt-dlp (סיבה נפוצה ל-403)..."
yt-dlp --rm-cache-dir >/dev/null 2>&1 || true

success=0
attempt_no=0
for client in "${CLIENTS[@]}"; do
  attempt_no=$(( attempt_no + 1 ))

  # מהניסיון השני מאטים: מקטע אחד בכל פעם והשהיה בין בקשות.
  if [[ $attempt_no -eq 1 ]]; then
    frags="$CONCURRENT"; sleep_req=0
  elif [[ $attempt_no -eq 2 ]]; then
    frags=2; sleep_req=1
  else
    frags=1; sleep_req=3
  fi

  rc=0
  attempt "$client" "$frags" "$sleep_req" || rc=$?
  if [[ $rc -eq 0 ]]; then
    success=1
    break
  fi

  warn "הניסיון עם client=${client} הסתיים עם קוד $rc"

  # אם לא נשאר מה להוריד — סיימנו, גם אם yt-dlp החזיר קוד שגיאה
  # (שגיאה על סרטון פרטי/מחוק אחד לא אמורה להיחשב כישלון).
  if [[ "$(remaining_count)" -le 0 ]]; then
    log "כל הפריטים הזמינים ירדו."
    success=1
    break
  fi

  log "מנקה cache ועובר ללקוח הבא..."
  yt-dlp --rm-cache-dir >/dev/null 2>&1 || true
  sleep 5
done

# מוצא אחרון: cookies מהדפדפן, אם המשתמש לא ביקש כאלה מלכתחילה.
if [[ $success -eq 0 && -z "$COOKIES_BROWSER" && -z "$COOKIES_FILE" ]]; then
  for browser in chrome firefox brave edge; do
    warn "מנסה שוב עם cookies מ-$browser (עוזר כשיוטיוב דורש אימות/גיל)"
    COOKIES_BROWSER="$browser"
    build_base_args
    rc=0
    attempt "tv" 1 3 || rc=$?
    if [[ $rc -eq 0 ]]; then success=1; break; fi
  done
fi

if [[ $success -eq 1 ]]; then
  log "הסתיים. הקבצים ב: $OUT_DIR"
  exit 0
fi

cat >&2 <<'EOF'

לא הצלחנו להשלים את ההורדה. מה שכדאי לנסות עכשיו, לפי הסדר:

  1. yt-dlp -U            — כמעט כל גל של 403 נפתר בגרסה חדשה.
  2. -c firefox / -c chrome — cookies של חשבון מחובר.
     (חשוב: לסגור את הדפדפן לפני, אחרת קובץ ה-cookies נעול.)
  3. -r 1M -n 1           — האטה; 403 מופיע גם כשמזהים קצב חשוד.
  4. VPN / רשת אחרת       — כתובות IP של ספקי ענן חסומות לעיתים קרובות.
  5. -i 1-5               — לחתוך את הפלייליסט לקבוצות קטנות.

הארכיון שומר מה שכבר ירד, אז אפשר פשוט להריץ שוב את אותה פקודה
וההורדה תמשיך מאיפה שנעצרה.
EOF
exit 1
