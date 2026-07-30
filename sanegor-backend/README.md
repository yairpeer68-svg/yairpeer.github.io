# סנגור — Backend

Backend של עוזר משפטי דיגיטלי לדין הישראלי: FastAPI + PostgreSQL/pgvector +
Redis + RAG מעל DeepSeek.

> **המערכת אינה מספקת ייעוץ משפטי.** כל תשובה היא מידע כללי בלבד ואינה תחליף
> לייעוץ פרטני מעורך דין מוסמך.

---

## עקרון מרכזי: אסמכתאות מהמאגר בלבד

הדרישה החשובה ביותר במפרט היא שהמערכת לא תמציא ציטוטים. הדרישה הזו נאכפת
במבנה הקוד ולא בבקשה מנומסת מהמודל:

1. `RagPipeline` מאחזר קטעים מטבלת `legal_chunks` ומספר אותם `[מקור 1..N]`.
2. הפרומפט מציג למודל **רק** את הקטעים האלה ואוסר עליו לצטט כל דבר אחר.
3. `app/services/ai/citations.py` בודק את פלט המודל מול הקבוצה שהוגשה בפועל.
   סימון שמצביע מחוץ לקבוצה (למשל `[מקור 9]` כשהוגשו 4) **נמחק מהתשובה**.
4. רשימת המקורות שמוצגת באפליקציה נבנית מהשורות שאוחזרו — לא מהטקסט שהמודל
   כתב.

ב-Streaming זה נאכף גם תוך כדי זרימת הטוקנים (`StreamingCitationGuard`), כולל
המקרה שבו סימון מקור נחתך בין שני חלקי זרם.

**כאשר המאגר ריק** המערכת אומרת זאת במפורש (`corpus_empty` בחיפוש) ועונה ברמה
עקרונית ללא אסמכתאות. אין ברירת מחדל שמייצרת "מקור" סינתטי.

---

## מה לא כלול, ולמה

| פריט | מצב | סיבה |
|---|---|---|
| טקסט חקיקה ופסיקה | **לא כלול** | לחומר משפטי יש רישוי ודרישות עדכניות משלו. תמונת מצב ישנה שנארזת ב-repo גרועה יותר מהיעדר חומר. יש לטעון דרך `scripts/seed_corpus.py`. |
| שירות Embeddings | נדרש חיצוני | ל-DeepSeek אין endpoint של embeddings. ראו למטה. |
| שליחת דוא״ל | לא ממומש | אסימוני אימות ואיפוס נכתבים ללוג; יש לחבר ספק SMTP/דוא״ל בייצור. |
| אימות OAuth מלא | חלקי | קיים `login_with_provider` בשכבת השירות; אימות ה-ID token מול Google/Apple טרם חובר. |

---

## Embeddings

DeepSeek אינו מספק embeddings, ולכן צד הווקטורים ניתן להחלפה:

| `EMBEDDING_PROVIDER` | שימוש |
|---|---|
| `openai_compatible` | כל שרת עם `POST {base_url}/embeddings` — TEI, Ollama, vLLM, LiteLLM proxy או ספק מנוהל. **חובה מודל רב-לשוני**; מודל אנגלי בלבד שובר אחזור בעברית. |
| `hashing` | Stub דטרמיניסטי לפיתוח ולבדיקות. ללא הבנה סמנטית כלל. נחסם בקונפיגורציית ייצור. |

`EMBEDDING_DIMENSIONS` נקבע ברוחב עמודת ה-`vector` בזמן המיגרציה. שינוי המודל
מחייב מיגרציה חדשה — ערבוב מרחבי וקטורים באותה עמודה הופך אחזור לשגוי בשקט.

---

## הפעלה מהירה

### Docker (מומלץ)

```bash
cp .env.example .env
# חובה: SECRET_KEY, ENCRYPTION_KEY, DEEPSEEK_API_KEY
python3 -c "import secrets; print(secrets.token_urlsafe(64))"                       # SECRET_KEY
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"  # ENCRYPTION_KEY

docker compose up -d postgres redis
docker compose run --rm migrate
docker compose up -d api

curl http://localhost:8000/health
open http://localhost:8000/docs
```

### מקומי

```bash
./scripts/install.sh
source .venv/bin/activate
docker compose up -d postgres redis
alembic upgrade head
uvicorn app.main:app --reload
```

### תלויות מערכת (ל-OCR ולייצוא PDF)

```bash
sudo apt install tesseract-ocr tesseract-ocr-heb tesseract-ocr-eng \
                 poppler-utils fonts-noto-core
```

בלי `fonts-noto-core` ייצוא PDF ירנדר ריבועים במקום עברית. בלי
`tesseract-ocr-heb` אין OCR בעברית.

---

## הרצה כשרת ביתי

```bash
./scripts/serve-lan.sh
```

מאזין על כל הממשקים, מזהה WSL2 ומדפיס את הכתובת שצריך לתת לאפליקציה, כולל
מה להגדיר ב-Windows כדי שהטלפון יוכל להגיע.

### שרת ביתי נגיש מבחוץ

LAN עובד רק בבית. כדי שהטלפון יגיע לשרת שרץ על המחשב גם מבחוץ, בלי VPS
ובלי לפתוח פורטים בראוטר:

* **Tailscale** — מתקינים על המחשב ועל הטלפון, שניהם מקבלים כתובת פרטית
  קבועה, והחיבור עובד מכל רשת. הכי פשוט, וגם מוצפן.
* **Cloudflare Tunnel** — נותן כתובת HTTPS אמיתית שמצביעה על השרת המקומי.
  מתאים גם כשרוצים לתת גישה למישהו אחר.

שתיהן חינמיות בשימוש אישי ופותרות גם את בעיית ה-HTTPS: עם כתובת מוצפנת,
גם build של release יעבוד מול השרת הביתי בלי היתר cleartext.

**מה שהן לא פותרות:** המחשב צריך להיות דלוק. זה בסדר לפיתוח ולשימוש אישי,
ולא מספיק כשיש משתמשים אחרים.

---

## טעינת המאגר המשפטי

```bash
python scripts/seed_corpus.py --check                 # מה יש במאגר כרגע
python scripts/seed_corpus.py corpus/legislation.json # טעינה
```

מבנה הקובץ מתועד בראש `scripts/seed_corpus.py`. שדות חובה:
`citation_key`, `title`, `content`, `source_type`, `publisher` — כאשר
`publisher` הוא דרישת מקוריות: אסמכתה שמוצגת למשתמש חייבת להיות ניתנת למעקב
אל המקור שממנו נטענה.

לאחר טעינה גדולה, על PostgreSQL:

```sql
REINDEX INDEX ix_legal_chunks_embedding;  -- IVFFlat צריך מרכזים על נתונים אמיתיים
```

ניתן גם דרך ה-API: `POST /api/v1/admin/corpus/ingest` (דורש הרשאת `admin`).

---

## ארכיטקטורה

```
app/
├── core/            קונפיגורציה, לוגים, שגיאות, אבטחה, rate limiting, middleware
├── db/              Base + מודלים (users, conversations, documents, legal corpus, audit)
├── schemas/         מודלי Pydantic — החוזה הציבורי של ה-API
├── services/
│   ├── ai/          לקוח DeepSeek, embeddings, פרומפטים, מנוע ציטוטים, ניהול Context
│   ├── rag/         אחזור (dense+lexical), דירוג, pipeline, ingest
│   ├── documents/   חילוץ טקסט, OCR, ייצוא, שירות המסמכים
│   ├── legal/       תבניות חוזים/מכתבים, ניסוח, ניתוח
│   ├── auth.py      הרשמה, התחברות, אסימונים
│   ├── chat.py      תזמור השיחה + Streaming
│   ├── storage.py   אחסון קבצים מאובטח
│   ├── cache.py     Redis (עם fallback שקט)
│   └── audit.py     יומן ביקורת
├── api/v1/routes/   נקודות הקצה
└── worker/          Celery אופציונלי
```

### זרימת RAG

```
שאלה → Embedding → חיפוש וקטורי (pgvector) ─┐
                 → חיפוש לקסיקלי (FTS)      ─┴→ RRF → משקלי סמכות → גיוון
                                                        ↓
                                        סינון לפי סף → בניית Prompt
                                                        ↓
                                        DeepSeek (Streaming) → אימות ציטוטים
                                                        ↓
                                                 שמירה + החזרה
```

חיפוש דו-ערוצי בכוונה: חיפוש וקטורי לבדו מפספס מזהים מדויקים
(`ע"א 1234/56`), וחיפוש מילולי לבדו מפספס ניסוח חלופי.

**דירוג לפי סמכות**: חקיקה ראשית > תקנות > פסיקה; עליון > מחוזי > שלום;
העדפת עדכניות מוחלת על פסיקה בלבד (לחוק, גיל אינו רלוונטי באותו אופן).

---

## API

| Method | Path | תיאור |
|---|---|---|
| POST | `/api/v1/auth/register` | הרשמה |
| POST | `/api/v1/auth/login` | התחברות |
| POST | `/api/v1/auth/refresh` | חידוש אסימון (רוטציה חד-פעמית) |
| POST | `/api/v1/auth/logout` | התנתקות |
| GET/PATCH | `/api/v1/auth/me` | פרופיל |
| POST | `/api/v1/chat` | צ'אט (SSE כברירת מחדל) |
| WS | `/api/v1/ws/chat` | צ'אט דו-כיווני עם ביטול |
| GET | `/api/v1/history` | רשימת שיחות |
| GET/PATCH/DELETE | `/api/v1/history/{id}` | שיחה בודדת |
| POST | `/api/v1/documents/upload` | העלאת מסמך (+OCR) |
| GET | `/api/v1/documents` | רשימת מסמכים |
| GET | `/api/v1/documents/{id}/text` | טקסט שחולץ |
| POST | `/api/v1/analysis/document` | ניתוח מסמך |
| POST | `/api/v1/analysis/contract` | ניתוח חוזה |
| POST | `/api/v1/analysis/case-summary` | סיכום פסיקה |
| GET | `/api/v1/contracts/templates` | תבניות חוזים (9) |
| POST | `/api/v1/contracts/generate` | יצירת חוזה |
| GET | `/api/v1/letters/templates` | תבניות מכתבים (10) |
| POST | `/api/v1/letters/generate` | יצירת מכתב |
| POST | `/api/v1/search` | חיפוש חקיקה ופסיקה |
| POST | `/api/v1/export` | ייצוא PDF/DOCX/MD |
| POST | `/api/v1/admin/corpus/ingest` | טעינת מקור (admin) |
| GET | `/health`, `/health/ready` | בריאות וזמינות |

Swagger מלא: `/docs` · OpenAPI: `/openapi.json`

### פורמט Streaming (SSE)

```
event: start   {"conversation_id","message_id","sources":[...],"grounded":true}
event: delta   {"text":"..."}
event: done    {"citations":[...],"disclaimer":"...","truncated":false}
event: error   {"code":"...","message":"..."}
```

`sources` נשלח **לפני** שהמודל התחיל לענות, כדי שהמשתמש יראה על מה התשובה
מתבססת מהפריים הראשון.

### מעטפת שגיאה אחידה

```json
{"error": {"code": "not_found", "message": "...", "details": {}, "request_id": "..."}}
```

---

## אבטחה

| נושא | מימוש |
|---|---|
| סיסמאות | Argon2id (memory-hard), rehash אוטומטי בשינוי פרמטרים |
| אסימונים | JWT קצר-מועד + refresh עם רוטציה חד-פעמית ו-`jti` במסד |
| נעילת חשבון | 8 כשלונות רצופים → נעילה ל-15 דקות |
| Enumeration | תשובה זהה לכתובת קיימת ולא קיימת, גם ב-login וגם באיפוס סיסמה |
| Rate limiting | Redis, חלון קבוע, דליים נפרדים ל-auth/AI/upload |
| SQL Injection | SQLAlchemy עם פרמטרים בלבד; אין הרכבת SQL ממחרוזות משתמש |
| XSS | ה-API מחזיר JSON בלבד; CSP חוסם הכול; `nosniff` |
| CSRF | אסימון Bearer בכותרת ולא בעוגייה — אין וקטור CSRF קלאסי |
| העלאות | בדיקת גודל → allow-list סוגים → אימות magic bytes; מפתח אחסון נגזר מ-hash ולא משם הקובץ |
| הצפנה במנוחה | Fernet על טקסט מסמכים (`ENCRYPTION_KEY`) |
| בידוד משתמשים | כל שאילתה מסוננת ב-`user_id`; משתמש אחר מקבל 404, לא 403 |
| RBAC | `guest < user < lawyer < admin`; שינוי תפקיד מבטל אסימונים קיימים |
| Audit log | טבלה append-only, ללא תוכן מסמכים וללא סודות |
| Headers | HSTS, CSP, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` |

הגדרות ייצור נבדקות בזמן עלייה (`Settings._production_guardrails`) — שרת
ייצור עם `DEBUG=true`, `CORS_ORIGINS=*` או `EMBEDDING_PROVIDER=hashing`
פשוט לא יעלה.

---

## בדיקות

```bash
pytest                       # כל הסוויטה
pytest -m integration        # בדיקות API מקצה לקצה
pytest --cov=app --cov-report=term-missing
```

הסוויטה רצה מול SQLite ולכן אינה דורשת PostgreSQL או Redis. הנתיבים
הייחודיים ל-PostgreSQL (pgvector ANN, FTS) נבדקים מול stack ה-compose.

```bash
ruff check app tests && ruff format --check app tests
mypy app
```

---

## פריסה לייצור

```bash
./scripts/deploy-linux.sh
```

הסקריפט מוודא קונפיגורציה, בונה, מריץ מיגרציות, מעלה ובודק readiness.

מה שנשאר בידיים שלכם:

* **TLS** — הקונטיינר מדבר HTTP; יש להעמיד לפניו nginx/Caddy/LB מנוהל.
  `FORCE_HTTPS=true` גורם לאפליקציה לדרוש `X-Forwarded-Proto` מה-proxy.
* **גיבויים** — `pg_dump` מתוזמן. גיבוי אוטומטי אינו מובנה בקומפוז.
* **לוגים** — `LOG_JSON=true` ואיסוף חיצוני.
* **ניטור** — `/health/ready` מ-uptime checker.

---

## רישיון והבהרה

הקוד מקורי במלואו. אין בו העתקה של קוד, עיצוב או נכסים ממערכות מסחריות.

המערכת היא כלי מידע. היא אינה עורכת דין, אינה מייצגת, ואינה יכולה להעריך
תיק קונקרטי. כל שימוש בפלט — חוזה, מכתב, ניתוח — מחייב בדיקה של עורך דין
מוסמך לפני הסתמכות.
