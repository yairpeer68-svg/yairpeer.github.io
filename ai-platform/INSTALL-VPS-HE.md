# התקנת AI Platform 2.1.1 על שרת Ubuntu VPS — מדריך מלא

מדריך מקצה לקצה. אם תעקוב אחרי כל השלבים לפי הסדר תקבל שרת פעיל עם HTTPS, פאנל ניהול,
ו־API מוכן לאפליקציית האנדרואיד.

**זמן משוער:** 30–45 דקות.

---

## תוכן עניינים

1. [מה צריך לפני שמתחילים](#1-מה-צריך-לפני-שמתחילים)
2. [הכנת השרת](#2-הכנת-השרת)
3. [חומת אש](#3-חומת-אש)
4. [העלאת הקבצים](#4-העלאת-הקבצים)
5. [קובץ ההגדרות `.env`](#5-קובץ-ההגדרות-env)
6. [התקנה ראשונה](#6-התקנה-ראשונה)
7. [הפעלת HTTPS](#7-הפעלת-https)
8. [יצירת משתמש מנהל](#8-יצירת-משתמש-מנהל)
9. [כניסה לפאנל הניהול](#9-כניסה-לפאנל-הניהול)
10. [חיבור אפליקציית האנדרואיד](#10-חיבור-אפליקציית-האנדרואיד)
11. [הפעלה אוטומטית באתחול](#11-הפעלה-אוטומטית-באתחול)
12. [גיבויים](#12-גיבויים)
13. [עדכון גרסה](#13-עדכון-גרסה)
14. [ניטור ותחזוקה](#14-ניטור-ותחזוקה)
15. [פתרון תקלות](#15-פתרון-תקלות)
16. [נספח: כל משתני הסביבה](#16-נספח-כל-משתני-הסביבה)

---

## 1. מה צריך לפני שמתחילים

### חומרה מינימלית

| משאב | מינימום | מומלץ |
|---|---|---|
| RAM | 4 GB | 8 GB |
| CPU | 2 ליבות | 4 ליבות |
| דיסק | 40 GB SSD | 80 GB SSD |
| מערכת | Ubuntu 22.04 LTS | Ubuntu 24.04 LTS |

> **למה 4GB מינימום:** PostgreSQL, Redis, ה־API, ה־worker וקונטיינר ההרצה המבודד רצים במקביל.
> אם תפעיל את מנוע ההנדסה האוטונומי על פרויקטים גדולים — 8GB.

### דברים שצריך להשיג מראש

1. **VPS** עם Ubuntu ו־IP ציבורי (Hetzner, DigitalOcean, Linode, AWS Lightsail — כולם מתאימים).
2. **דומיין** שמפנה ל־IP של השרת. בלי דומיין אי אפשר לקבל תעודת HTTPS.
3. **מפתח DeepSeek API** מ־https://platform.deepseek.com (אופציונלי — אפשר להתקין בלעדיו
   ולהוסיף אחר כך, אבל בלי מפתח ה־AI לא יעבוד).

### הגדרת ה־DNS

אצל ספק הדומיין צור רשומת **A**:

```
סוג    שם              ערך                TTL
A      api             203.0.113.10       300
```

תחליף `203.0.113.10` ב־IP של השרת. התוצאה: `api.yourdomain.com`.

בדוק שזה עובד לפני שממשיכים:

```bash
dig +short api.yourdomain.com
```

צריך להחזיר את ה־IP שלך. אם לא — חכה כמה דקות והתפשטות ה־DNS תסתיים.

---

## 2. הכנת השרת

התחבר לשרת:

```bash
ssh root@203.0.113.10
```

### עדכון המערכת

```bash
apt-get update && apt-get upgrade -y
```

### יצירת משתמש לא־root (מומלץ מאוד)

הרצת הכול כ־root היא סיכון מיותר:

```bash
adduser --gecos "" aiplatform
usermod -aG sudo aiplatform
rsync -a --chown=aiplatform:aiplatform ~/.ssh /home/aiplatform/
```

עכשיו התנתק והתחבר מחדש כמשתמש החדש:

```bash
exit
ssh aiplatform@203.0.113.10
```

### הקשחת SSH (מומלץ)

```bash
sudo nano /etc/ssh/sshd_config
```

שנה את השורות הבאות:

```
PermitRootLogin no
PasswordAuthentication no
```

> **אזהרה:** אל תכבה `PasswordAuthentication` לפני שווידאת שהכניסה עם מפתח SSH עובדת,
> אחרת תינעל מחוץ לשרת.

```bash
sudo systemctl restart ssh
```

### עדכוני אבטחה אוטומטיים

```bash
sudo apt-get install -y unattended-upgrades
sudo dpkg-reconfigure --priority=low unattended-upgrades
```

---

## 3. חומת אש

```bash
sudo apt-get install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status verbose
```

**מה פתוח ולמה:**

| פורט | תפקיד |
|---|---|
| 22 | SSH |
| 80 | הפניה ל־HTTPS + אימות Let's Encrypt |
| 443 | ה־API והפאנל |

**מה נשאר סגור:** 5432 (PostgreSQL), 6379 (Redis), 8080 (ה־API), 8090 (קונטיינר ההרצה).
כל אלה חשופים רק לרשתות הפנימיות של Docker. **לעולם אל תפתח אותם לאינטרנט.**

---

## 4. העלאת הקבצים

### אפשרות א' — העתקה מהמחשב שלך

מהמחשב המקומי:

```bash
scp ai-platform-server.zip aiplatform@203.0.113.10:~/
```

ואז בשרת:

```bash
sudo apt-get install -y unzip
unzip ai-platform-server.zip
sudo mkdir -p /opt
sudo mv ai-platform /opt/ai-platform
sudo chown -R "$USER:$USER" /opt/ai-platform
cd /opt/ai-platform
```

### אפשרות ב' — מ־Git

```bash
sudo apt-get install -y git
sudo git clone <כתובת-הריפו> /opt/ai-platform
sudo chown -R "$USER:$USER" /opt/ai-platform
cd /opt/ai-platform
```

### בדיקה

```bash
ls
```

צריך לראות: `server` `runner` `admin` `deploy` `scripts` `docs` `docker-compose.yml` `.env.example`

---

## 5. קובץ ההגדרות `.env`

זה השלב הכי חשוב. **טעות כאן = השרת לא יעלה.**

```bash
cp .env.example .env
chmod 600 .env
```

### יצירת סודות חזקים

הרץ את הפקודות הבאות ושמור את הפלט — תצטרך אותו מיד:

```bash
echo "JWT_SECRET=$(python3 -c 'import secrets;print(secrets.token_urlsafe(64))')"
echo "POSTGRES_PASSWORD=$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
echo "ENGINEERING_RUNNER_TOKEN=$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
echo "METRICS_TOKEN=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')"
```

> **חשוב:** אל תמציא סיסמאות בעצמך ואל תשתמש באותה סיסמה פעמיים.
> אימות ההגדרות יסרב לעלות בסביבת ייצור אם `JWT_SECRET` קצר מ־64 תווים או מכיל
> את המילה `change`.

### עריכת הקובץ

```bash
nano .env
```

**השדות שחייבים לשנות:**

```ini
# --- סביבה ---
APP_ENV=production
SERVER_NAME=api.yourdomain.com
APP_BASE_URL=https://api.yourdomain.com

# --- מסד נתונים ---
POSTGRES_DB=ai_platform
POSTGRES_USER=ai_platform
POSTGRES_PASSWORD=<הדבק כאן>
DATABASE_URL=postgresql+asyncpg://ai_platform:<אותה סיסמה>@postgres:5432/ai_platform

# --- אבטחה ---
JWT_SECRET=<הדבק כאן — לפחות 64 תווים>
METRICS_TOKEN=<הדבק כאן>

# --- רשת ---
TRUSTED_HOSTS=api.yourdomain.com
CORS_ORIGINS=https://api.yourdomain.com

# --- DeepSeek ---
DEEPSEEK_API_KEY=sk-<המפתח שלך>

# --- מנוע ההנדסה ---
ENGINEERING_RUNNER_TOKEN=<הדבק כאן — לפחות 32 תווים>
```

> **`DATABASE_URL`:** שים לב שהסיסמה מופיעה **פעמיים** — גם ב־`POSTGRES_PASSWORD`
> וגם בתוך ה־URL. הן חייבות להיות זהות.
> אם הסיסמה מכילה תווים מיוחדים כמו `@` או `/`, צריך לקודד אותם ב־URL־encoding.
> הכי פשוט: השתמש ב־`token_urlsafe` כמו למעלה, שמייצר רק תווים בטוחים.

> **`CORS_ORIGINS`:** אם הפאנל מוגש מאותו דומיין (ברירת המחדל) — זו הכתובת של השרת.
> אימות ההגדרות **יסרב** ל־`*` בסביבת ייצור.

### שמירה ובדיקה

`Ctrl+O`, `Enter`, `Ctrl+X`.

בדוק שאין ערכי `CHANGE_ME` שנשכחו:

```bash
grep -n "CHANGE_ME" .env
```

**הפקודה צריכה לא להחזיר כלום.** אם היא מחזירה שורות — תקן אותן.

---

## 6. התקנה ראשונה

סקריפט ההתקנה מתקין את Docker (אם חסר), בונה את הקונטיינרים, מריץ מיגרציות ומעלה הכול:

```bash
./INSTALL_ALL.sh
```

**מה קורה כאן, שלב אחר שלב:**

1. מתקין Docker Engine + Compose plugin
2. מוודא שההרשאות על `.env` ותיקיית הגיבויים נכונות
3. מעלה PostgreSQL ו־Redis ומחכה שיהיו תקינים
4. בונה את הקונטיינרים: `api`, `worker`, `scheduler`, `runner`, `nginx`
5. מריץ `alembic upgrade head` — יוצר את כל הטבלאות
6. מעלה את כל השירותים
7. מחכה ש־`/health/ready` יחזיר תקין

**זה לוקח 5–15 דקות** בפעם הראשונה (בניית קונטיינרים).

### בדיקה

```bash
./VERIFY_INSTALL.sh
```

הפלט צריך להיראות כך:

```
Docker                        OK
Compose config                OK
PostgreSQL                    OK
Redis                         OK
Isolated runner               OK
API liveness                  OK
API readiness                 OK
Nginx/local gateway           OK
Admin console assets          OK
Scheduler running             OK
```

אם משהו `FAIL` — קפוץ ל־[פתרון תקלות](#15-פתרון-תקלות).

### בדיקה ידנית

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/health/ready
curl http://127.0.0.1:8080/version
```

---

## 7. הפעלת HTTPS

עד עכשיו השרת מאזין רק ל־`127.0.0.1:8080` (לא חשוף לאינטרנט). עכשיו נוסיף TLS אמיתי.

### התקנת certbot והנפקת תעודה

```bash
sudo apt-get install -y certbot
sudo mkdir -p /var/www/certbot

# עצירה זמנית של nginx כדי לשחרר את פורט 80
docker compose stop nginx

sudo certbot certonly --standalone \
  -d api.yourdomain.com \
  --agree-tos \
  -m you@yourdomain.com \
  --non-interactive
```

**אם זה נכשל:** ודא ש־DNS מצביע נכון (`dig +short api.yourdomain.com`) ושפורט 80 פתוח
בחומת האש **וגם** אצל ספק ה־VPS (חלק מהספקים מפעילים חומת אש נוספת בפאנל שלהם).

### הפעלת ה־overlay של הייצור

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

עכשיו nginx מאזין ל־80 (מפנה ל־HTTPS) ול־443 עם התעודה.

### בדיקה מבחוץ

מהמחשב שלך, לא מהשרת:

```bash
curl https://api.yourdomain.com/health
curl https://api.yourdomain.com/version
```

צריך להחזיר `{"status":"ok"}` ואת פרטי הגרסה.

### חידוש אוטומטי של התעודה

התעודה תקפה ל־90 יום. נגדיר חידוש אוטומטי:

```bash
sudo tee /etc/systemd/system/certbot-renew.service >/dev/null <<'EOF'
[Unit]
Description=Renew Let's Encrypt certificates for AI Platform
[Service]
Type=oneshot
WorkingDirectory=/opt/ai-platform
ExecStart=/usr/bin/certbot renew --quiet --deploy-hook "cd /opt/ai-platform && /usr/bin/docker compose -f docker-compose.yml -f docker-compose.prod.yml restart nginx"
EOF

sudo tee /etc/systemd/system/certbot-renew.timer >/dev/null <<'EOF'
[Unit]
Description=Twice-daily Let's Encrypt renewal check
[Timer]
OnCalendar=*-*-* 03,15:00:00
RandomizedDelaySec=1h
Persistent=true
[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now certbot-renew.timer
sudo systemctl list-timers certbot-renew.timer
```

בדיקה יבשה:

```bash
sudo certbot renew --dry-run
```

> **למה `--deploy-hook`:** nginx טוען את התעודה לזיכרון בעלייה. בלי הפעלה מחדש
> אחרי החידוש, הוא ימשיך להגיש את התעודה הישנה עד שיפול או יופעל מחדש —
> כלומר האפליקציה תפסיק לעבוד בערך 90 יום אחרי ההתקנה.

---

## 8. יצירת משתמש מנהל

```bash
docker compose exec -T api python -m app.cli create-admin
```

הסקריפט יבקש אימייל וסיסמה. **הסיסמה נקראת בלי הדפסה למסך ולא נשמרת בהיסטוריית הפקודות.**

דרישות הסיסמה: לפחות 10 תווים, לפחות 5 תווים שונים, לא סיסמה נפוצה.

בדיקה:

```bash
docker compose exec -T api python -m app.cli list-users
```

---

## 9. כניסה לפאנל הניהול

פתח בדפדפן:

```
https://api.yourdomain.com/admin/
```

> שים לב ל־`/` בסוף. בלעדיו חלק מהדפדפנים לא יטענו את הנתיב נכון.

התחבר עם המשתמש שיצרת. אם המשתמש אינו מנהל — הפאנל ידחה את הכניסה וינתק את ה־session
בצד השרת.

### מה יש בפאנל

| מסך | תפקיד |
|---|---|
| Dashboard | מצב המערכת ושימוש ב־AI ב־7 ימים |
| Users | רשימת משתמשים, הפעלה/השבתה, הרשאות |
| Devices | מכשירים רשומים וביטולם |
| AI usage / Quotas | צריכה ומכסות לכל משתמש |
| Feature flags | דגלי פיצ'רים והשקה הדרגתית |
| Security events | אירועי אבטחה (כשלי התחברות, שימוש חוזר באסימון) |
| Audit logs | יומן פעולות |
| System health | מצב + מצב תחזוקה |
| **Engineering projects** | יצירת פרויקט, **ייבוא ZIP של קוד**, הפעלת run |
| **Engineering approvals** | **אישור/דחייה של פקודות** שסוכן ביקש להריץ |

> **חשוב על אישורים:** `ENGINEERING_AUTO_EXECUTE_COMMANDS=false` כברירת מחדל, כלומר
> כשסוכן מבקש להריץ פקודה — ה־run **נעצר** עד שמישהו יאשר או ידחה במסך
> "Engineering approvals". אישור לעולם לא מרחיב את מדיניות ההרצה: פקודה שאינה
> ברשימה המותרת נשארת חסומה גם אם אישרת אותה.

> **הערה על התחברות לפאנל:** האסימונים נשמרים בזיכרון הדפדפן בלבד ולא ב־localStorage.
> זו החלטה אבטחתית מכוונת — המשמעות היא שרענון הדף מנתק אותך ותצטרך להתחבר שוב.

---

## 10. חיבור אפליקציית האנדרואיד

### קבלת ה־APK

**דרך א' — GitHub Actions (הכי פשוט, בלי להתקין כלום):**

1. העלה את הפרויקט ל־GitHub
2. `Actions` → `Build APK` → `Run workflow`
3. אפשר להשאיר את `api_base_url` ריק — האפליקציה תשאל לכתובת בהפעלה הראשונה
4. כשהריצה מסתיימת, הורד את ה־artifact

**דרך ב' — בנייה מקומית על אובונטו:**

```bash
cd android
./build-apk.sh
```

הסקריפט מתקין Flutter ואת ה־Android SDK לתיקייה מקומית (`~/.ai-platform-build`),
מריץ את כל הבדיקות ובונה APK. אפשר גם לקבע כתובת שרת:

```bash
./build-apk.sh https://api.yourdomain.com/api/v1
```

### התקנה על המכשיר

העתק את ה־APK לטלפון והתקן, או:

```bash
adb install -r app-release.apk
```

צריך לאפשר "התקנה ממקורות לא ידועים" בהגדרות האנדרואיד.

### הפעלה ראשונה

האפליקציה תציג מסך **"התחברות לשרת שלך"**. הזן:

```
api.yourdomain.com
```

זה מספיק — האפליקציה משלימה לבד ל־`https://api.yourdomain.com/api/v1`, בודקת שהשרת
עונה ומציגה את הגרסה שזוהתה.

> **חייב HTTPS.** האפליקציה חוסמת תעבורה לא מוצפנת (`usesCleartextTraffic=false`),
> ולכן כתובת `http://` תידחה עם הסבר במקום להיתקע בטיימאאוט.

אפשר להחליף שרת בכל רגע דרך `הגדרות` → `כתובת השרת`.

### מפתח חתימה (אם רוצים לפרסם ב־Play Store)

```bash
keytool -genkey -v -keystore upload-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

צור `android/android/key.properties`:

```ini
storePassword=<הסיסמה>
keyPassword=<הסיסמה>
keyAlias=upload
storeFile=/absolute/path/to/upload-keystore.jks
```

> **גבה את הקובץ `.jks` ואת הסיסמאות במקום בטוח.** אם תאבד אותם לא תוכל לעולם
> לפרסם עדכון לאפליקציה הקיימת ב־Play Store.
> **אל תכניס את הקובץ הזה ל־Git.**

בלי `key.properties` הבנייה תיעצר עם הודעה ברורה. אם אתה רוצה APK לא־חתום לבדיקה
מקומית בלבד, הוסף `-PALLOW_UNSIGNED_RELEASE=true`.

---

## 11. הפעלה אוטומטית באתחול

```bash
sudo cp deploy/systemd/ai-platform.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable ai-platform.service
sudo systemctl start ai-platform.service
sudo systemctl status ai-platform.service
```

> ודא ש־`WorkingDirectory` בקובץ מצביע ל־`/opt/ai-platform`. אם התקנת במקום אחר —
> ערוך את הקובץ לפני ההפעלה.

---

## 12. גיבויים

### גיבוי ידני

```bash
./scripts/backup.sh
```

**מה נשמר — שני קבצים, שניהם נחוצים:**

1. `ai_platform-<תאריך>.dump` — מסד הנתונים
2. `workspaces-<תאריך>.tar.zst` — קוד המקור של הפרויקטים והיסטוריית ה־Git שלהם

> **קריטי:** גיבוי של מסד הנתונים בלבד **אינו** נקודת שחזור שלמה. תקבל רשומות
> שמתארות פרויקטים ו־checkpoints שהקבצים שלהם לא קיימים, ושחזור לנקודה קודמת
> (rollback) ייכשל.

### אימות גיבוי

```bash
./scripts/verify-backup.sh backups/ai_platform-<תאריך>.dump backups/workspaces-<תאריך>.tar.zst
```

### גיבוי אוטומטי יומי

```bash
sudo tee /etc/systemd/system/ai-platform-backup.service >/dev/null <<'EOF'
[Unit]
Description=AI Platform nightly backup
[Service]
Type=oneshot
User=aiplatform
WorkingDirectory=/opt/ai-platform
ExecStart=/opt/ai-platform/scripts/backup.sh
EOF

sudo tee /etc/systemd/system/ai-platform-backup.timer >/dev/null <<'EOF'
[Unit]
Description=Run the AI Platform backup nightly
[Timer]
OnCalendar=*-*-* 02:30:00
RandomizedDelaySec=30m
Persistent=true
[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now ai-platform-backup.timer
```

> החלף `aiplatform` בשם המשתמש שלך.

### שחזור

```bash
./scripts/restore.sh backups/ai_platform-<תאריך>.dump backups/workspaces-<תאריך>.tar.zst
```

הסקריפט **עוצר בעצמו** את `api`, `worker` ו־`scheduler` לפני מחיקת מסד הנתונים,
ומחזיר אותם בסוף. הוא ידרוש שתקליד `RESTORE` לאישור.

> **גיבוי אינו מוכח עד ששחזרת ממנו.** תרגל שחזור על שרת נפרד לפחות פעם ברבעון.

### עותק מחוץ לשרת

הגדר ב־`.env`:

```ini
BACKUP_UPLOAD_DIR=/mnt/backup-remote
```

וחבר שם אחסון מרוחק (NFS/SSHFS/rclone mount). **גיבוי שיושב רק על השרת שהוא מגבה
אינו גיבוי.**

---

## 13. עדכון גרסה

```bash
cd /opt/ai-platform
./UPGRADE.sh
```

**מה הסקריפט עושה:**

1. יוצר גיבוי לפני שנוגע במשהו
2. מושך גרסה חדשה (אם זה Git repo)
3. מוודא שקבצי ה־compose תקינים
4. בונה קונטיינרים — **אם הבנייה נכשלת, השירותים הקיימים ממשיכים לרוץ**
5. מריץ מיגרציות
6. מעלה את השירותים ומריץ `VERIFY_INSTALL.sh`

בכל כשל הוא עוצר ומדפיס את נתיב הגיבוי.

---

## 14. ניטור ותחזוקה

### לוגים

```bash
docker compose logs -f api        # ה־API
docker compose logs -f worker     # משימות רקע
docker compose logs -f scheduler  # ניקוי נתונים מתוזמן
docker compose logs -f runner     # קונטיינר ההרצה המבודד
docker compose logs --tail=200    # הכול
```

הלוגים בפורמט JSON, עם השמטה אוטומטית של סיסמאות, אסימונים ומפתחות API.

### מצב השירותים

```bash
docker compose ps
docker stats --no-stream
df -h
```

### Prometheus + Grafana (אופציונלי)

```bash
docker compose --profile observability up -d
```

Grafana יהיה זמין ב־`127.0.0.1:3000`. **הוא לא חשוף לאינטרנט בכוונה** — גש אליו
דרך מנהרת SSH:

```bash
ssh -L 3000:127.0.0.1:3000 aiplatform@203.0.113.10
```

ואז פתח `http://localhost:3000` בדפדפן המקומי.

### שמירת נתונים (retention)

שירות ה־`scheduler` מוחק אוטומטית, פעם ביום:

| נתון | חלון ברירת מחדל | משתנה |
|---|---|---|
| יומני ביקורת ואירועי אבטחה | 365 יום | `AUDIT_RETENTION_DAYS` |
| מטא־דאטה של בקשות AI | 90 יום | `AI_METADATA_RETENTION_DAYS` |
| אסימונים שפגו | מיידי | — |

> **שים לב:** אם הקונטיינר `scheduler` לא רץ, שום חלון שמירה לא נאכף.
> בדוק עם `docker compose ps scheduler`.

### מצב תחזוקה

בפאנל: `System health` → `Maintenance mode`. משתמשים יקבלו 503 עם הודעה,
אבל מסלולי הניהול ובדיקות הבריאות ימשיכו לעבוד.

---

## 15. פתרון תקלות

### `docker compose config` נכשל

```bash
docker compose config
```

הודעת השגיאה מצביעה על השורה. בדרך כלל: משתנה חסר ב־`.env` או ציטוט שבור.

---

### ה־API לא עולה

```bash
docker compose logs api --tail=80
```

| הודעה | הסיבה |
|---|---|
| `JWT_SECRET must be at least 64 strong characters` | סוד קצר מדי או מכיל `change` |
| `Wildcard CORS is forbidden in production` | `CORS_ORIGINS=*` |
| `Production APP_BASE_URL must use HTTPS` | `APP_BASE_URL` מתחיל ב־`http://` |
| `ENGINEERING_RUNNER_TOKEN must be a strong secret` | אסימון קצר מ־32 תווים או מכיל `change` |
| `Prompt retention requires PROMPT_RETENTION_ENCRYPTION_KEY` | `PROMPT_LOGGING_ENABLED=true` בלי מפתח |

כל אלה הן בדיקות מכוונות שמונעות עלייה עם הגדרות לא בטוחות.

---

### `/health/ready` מחזיר 503

```bash
curl -s http://127.0.0.1:8080/health/ready | python3 -m json.tool
```

- `"database": "unavailable"` → `docker compose logs postgres`. לרוב סיסמה שגויה
  ב־`DATABASE_URL` (לא תואמת ל־`POSTGRES_PASSWORD`).
- `"redis": "unavailable"` → `docker compose logs redis`.

---

### התעודה לא הונפקה

```bash
dig +short api.yourdomain.com     # חייב להחזיר את ה-IP שלך
sudo ufw status                    # 80 חייב להיות פתוח
```

ודא גם שחומת האש **בפאנל של ספק ה־VPS** פתוחה — זו חומת אש נפרדת מ־ufw
ותופסת אנשים לא מעט.

---

### הפאנל מציג דף לבן

```bash
curl -s https://api.yourdomain.com/admin/ | grep -o '/admin/assets/[^"]*'
```

צריך להחזיר נתיב כמו `/admin/assets/index-XXXX.js`. אם ריק — בנה מחדש:

```bash
docker compose build --no-cache nginx
docker compose up -d nginx
```

---

### העלאת ZIP נכשלת עם 413

`ENGINEERING_MAX_ARCHIVE_MB` ב־`.env` חייב להיות **גדול או שווה** ל־
`ENGINEERING_MAX_ARCHIVE_BYTES` (במגה־בייט). ברירת המחדל: `100` מול `100000000`.

אחרי שינוי:

```bash
docker compose up -d nginx
```

---

### run של מנוע ההנדסה נכשל על "toolchain"

זה **צפוי** לפרויקטי Flutter/Android. תמונת ההרצה הסטנדרטית כוללת רק Python ו־Node.

- אם אתה רק רוצה שה־run יסתיים — זו כבר ברירת המחדל
  (`ENGINEERING_STRICT_TOOLCHAINS=false`), והשער יסומן `toolchain_missing` בלי להכשיל.
- אם אתה **צריך** שהבדיקות של Flutter יורצו באמת:

```bash
echo "RUNNER_DOCKERFILE=Dockerfile.flutter" >> .env
echo "ENGINEERING_STRICT_TOOLCHAINS=true" >> .env
docker compose build runner
docker compose up -d runner
```

> התמונה הזו שוקלת כמה ג'יגה־בייט ולוקחת זמן לבנות. לכן היא לא ברירת המחדל.

---

### נגמר מקום בדיסק

```bash
df -h
docker system df
docker system prune -a --volumes    # זהירות: מוחק תמונות ו-volumes לא בשימוש
```

בדוק גם את גודל ה־workspaces:

```bash
docker run --rm -v ai-platform_engineering_workspaces:/w:ro alpine du -sh /w
```

הגבל עם `ENGINEERING_MAX_WORKSPACE_BYTES` ו־`ENGINEERING_MAX_PROJECTS_PER_USER`.

---

### `dropdb` נכשל בשחזור

אם הודעת השגיאה היא "database is being accessed by other users" — הרצת את `pg_restore`
ידנית במקום דרך הסקריפט. השתמש ב־`./scripts/restore.sh`, שעוצר את השירותים בעצמו.

---

## 16. נספח: כל משתני הסביבה

### חובה בייצור

| משתנה | תיאור |
|---|---|
| `APP_ENV` | חייב להיות `production` |
| `SERVER_NAME` | הדומיין, ל־nginx ולתעודה |
| `APP_BASE_URL` | `https://<דומיין>` — חייב HTTPS |
| `JWT_SECRET` | ≥64 תווים אקראיים |
| `POSTGRES_PASSWORD` + `DATABASE_URL` | חייבות להיות תואמות |
| `TRUSTED_HOSTS` | הדומיין. `*` נדחה |
| `CORS_ORIGINS` | מקורות מפורשים. `*` נדחה |
| `ENGINEERING_RUNNER_TOKEN` | ≥32 תווים |

### AI

| משתנה | ברירת מחדל | תיאור |
|---|---|---|
| `DEEPSEEK_API_KEY` | ריק | בלעדיו ה־AI מחזיר "לא מוגדר" |
| `DEEPSEEK_MODEL` | `deepseek-chat` | חייב להופיע ב־`DEEPSEEK_ALLOWED_MODELS` |
| `AI_RATE_LIMIT_PER_MINUTE` | 20 | בקשות לדקה למשתמש |
| `AI_MAX_PROMPT_CHARS` | 20000 | גודל הנחיה מרבי |
| `AI_CACHE_TTL_SECONDS` | 300 | מטמון תשובות |

### מנוע ההנדסה

| משתנה | ברירת מחדל | תיאור |
|---|---|---|
| `ENGINEERING_ENABLED` | `true` | הפעלה/כיבוי כללי |
| `ENGINEERING_AUTO_EXECUTE_COMMANDS` | `false` | **השאר false.** `true` מריץ פקודות שסוכן ביקש בלי אישור אנושי |
| `ENGINEERING_STRICT_TOOLCHAINS` | `false` | האם כלי בנייה חסר מכשיל את ה־run |
| `ENGINEERING_MAX_ARCHIVE_BYTES` | 100000000 | גודל ZIP מרבי |
| `ENGINEERING_MAX_ARCHIVE_MB` | 100 | **חייב להיות ≥ הקודם** |
| `ENGINEERING_MAX_WORKSPACE_BYTES` | 2000000000 | מכסת אחסון לפרויקט |
| `ENGINEERING_MAX_PROJECTS_PER_USER` | 20 | תקרת פרויקטים לחשבון |
| `ENGINEERING_MAX_ACTIVE_RUNS_PER_USER` | 2 | ריצות במקביל לחשבון |
| `ENGINEERING_RUN_TIMEOUT_SECONDS` | 3600 | תקרת זמן ל־run |
| `RUNNER_DOCKERFILE` | `Dockerfile` | `Dockerfile.flutter` לתמיכה ב־Flutter/Android |

### פרטיות ושמירת נתונים

| משתנה | ברירת מחדל | תיאור |
|---|---|---|
| `PROMPT_LOGGING_ENABLED` | `false` | **השאר false** אלא אם יש סיבה מאושרת |
| `PROMPT_RETENTION_ENCRYPTION_KEY` | ריק | מפתח Fernet — חובה אם הקודם `true` |
| `AUDIT_RETENTION_DAYS` | 365 | |
| `AI_METADATA_RETENTION_DAYS` | 90 | |
| `RETENTION_SWEEP_INTERVAL_SECONDS` | 86400 | תדירות הניקוי |

### משאבים

| משתנה | ברירת מחדל |
|---|---|
| `API_MEM_LIMIT` | `1g` |
| `WORKER_MEM_LIMIT` | `1g` |
| `RUNNER_MEM_LIMIT` | `4g` |
| `RUNNER_CPUS` | `2.0` |
| `FORWARDED_ALLOW_IPS` | `nginx` — **אל תשנה ל־`*`** |

---

## סיכום — רשימת בדיקה

- [ ] DNS מצביע לשרת
- [ ] חומת אש: רק 22, 80, 443 פתוחים
- [ ] `.env` בהרשאות 600, בלי אף `CHANGE_ME`
- [ ] `./VERIFY_INSTALL.sh` — הכול OK
- [ ] `https://<דומיין>/health` עונה מבחוץ
- [ ] טיימר חידוש התעודה פעיל
- [ ] משתמש מנהל נוצר, כניסה לפאנל עובדת
- [ ] גיבוי אוטומטי מוגדר **ונבדק בשחזור**
- [ ] העתק גיבוי מחוץ לשרת
- [ ] `systemctl enable ai-platform` — עולה אחרי אתחול
- [ ] האפליקציה מתחברת לשרת

---

## תיעוד נוסף

| קובץ | נושא |
|---|---|
| `docs/DEPLOYMENT.md` | פריסה מפורטת |
| `docs/SECURITY_HARDENING.md` | הקשחה |
| `docs/SECURITY_REVIEW.md` | סקירת אבטחה וסיכונים שנותרו |
| `docs/BACKUP.md` | גיבוי ושחזור |
| `docs/DISASTER_RECOVERY.md` | התאוששות מאסון |
| `docs/ENGINEERING_RUNTIME.md` | מנוע ההנדסה האוטונומי |
| `docs/API.md` | ה־API |
| `docs/ADMIN.md` | פאנל הניהול |
| `docs/TROUBLESHOOTING.md` | תקלות נוספות |
