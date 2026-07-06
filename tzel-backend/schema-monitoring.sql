-- צל דיגיטלי — עדכון סכמה לשלב 4 (ניטור מתמשך)
-- מריצים פעם אחת ב-SQL Editor, אחרי schema.sql הראשי.

-- כדי לבדוק מייל מול HIBP באופן חוזר צריך לשמור את המייל עצמו (HIBP בודק לפי כתובת, לא גיבוב).
-- המייל מוגן ב-Row Level Security ונשמר רק כשהמשתמש מפעיל ניטור מרצונו.
alter table public.monitors add column if not exists email text;
alter table public.monitors add column if not exists last_checked timestamptz;

-- אינדקס לסריקה יעילה של הפריטים הפעילים בלבד
create index if not exists monitors_active_idx on public.monitors (active) where active = true;
