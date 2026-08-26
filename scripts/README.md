# scripts

## `yt-playlist-dl.sh` — הורדת פלייליסט מיוטיוב עם עמידות ל‑403

### התקנה

```bash
pipx install yt-dlp        # או: pip install -U yt-dlp / brew install yt-dlp
brew install ffmpeg        # או: sudo apt install ffmpeg
chmod +x scripts/yt-playlist-dl.sh
```

### שימוש

```bash
# הורדה רגילה עד 1080p
./scripts/yt-playlist-dl.sh "https://www.youtube.com/playlist?list=PLxxxx"

# אודיו בלבד (mp3) לתיקייה מסוימת, עם עדכון yt-dlp לפני
./scripts/yt-playlist-dl.sh "https://youtube.com/playlist?list=PLxxxx" -o ~/Music -a -U

# עם cookies מהדפדפן והאטת קצב — הקומבינציה שהכי עוזרת מול חסימות
./scripts/yt-playlist-dl.sh "https://youtube.com/playlist?list=PLxxxx" -c firefox -r 2M

# רק פריטים 1‑10, עם כתוביות
./scripts/yt-playlist-dl.sh "https://youtube.com/playlist?list=PLxxxx" -i 1-10 -s

# להעביר דגלים נוספים ישירות ל-yt-dlp
./scripts/yt-playlist-dl.sh "https://youtube.com/playlist?list=PLxxxx" -- --write-thumbnail
```

`-h` מציג את רשימת האפשרויות המלאה.

### למה מקבלים 403 ואיך הסקריפט מטפל בזה

יוטיוב לא מגיש את הווידאו מכתובת קבועה: הוא מייצר URL חתום, קצר‑מועד,
שנקשר ללקוח (player client) שביקש אותו ולפעמים גם ל‑IP. `403 Forbidden`
מופיע כשהחתימה כבר לא תקפה עבור מי שמוריד בפועל. הסיבות הנפוצות:

| סיבה | מה הסקריפט עושה |
|---|---|
| cache מקומי עם חתימות/`nsig` ישנים | `--rm-cache-dir` לפני כל סבב |
| ה־player client שנבחר לא מתאים למה שמורידים | סבב על `tv → web_safari → mweb → ios → android_vr → web` |
| חתימה שפגה באמצע הורדה ארוכה | `--fragment-retries 50` + `--retry-sleep http:exp=1:60` |
| יותר מדי בקשות במקביל → זיהוי כבוט | מהניסיון השני: `--concurrent-fragments 1` ו‑`--sleep-requests` |
| תוכן שדורש התחברות / אימות גיל | נפילה חזרה ל‑`--cookies-from-browser` |
| IPv6 של הספק חסום ביוטיוב | `--force-ipv4` |
| גרסת yt-dlp ישנה אחרי שינוי בצד יוטיוב | `-U` מעדכן לפני ההורדה |

בין הניסיונות נשמר ארכיון (`<תיקיית יעד>/.downloaded.txt`), כך שסרטון
שכבר ירד לא יורד שוב — אפשר להריץ את אותה פקודה שוב ושוב וההורדה
תמשיך מאיפה שנעצרה. `--ignore-errors` דואג שסרטון פרטי או מחוק בתוך
הפלייליסט לא יעצור את השאר.

### כשעדיין נתקעים

1. `yt-dlp -U` — רוב גלי ה‑403 נסגרים בגרסה חדשה תוך יום‑יומיים.
2. `-c firefox` / `-c chrome` — צריך לסגור את הדפדפן קודם, אחרת קובץ ה‑cookies נעול.
3. `-r 1M -n 1` — האטה אגרסיבית.
4. רשת אחרת או VPN — כתובות IP של ספקי ענן וחוות שרתים חסומות לעיתים קרובות.
5. `-i 1-5`, `-i 6-10` — לחתוך את הפלייליסט לקבוצות קטנות.

> להורדה של תוכן שיש לך זכות להוריד — סרטונים שלך, תוכן ברישיון פתוח,
> או שימוש שמותר לפי תנאי השירות והדין החל.
