-- מאיה: סכמת מסד הנתונים (Supabase / PostgreSQL)
-- מריצים פעם אחת ב-SQL Editor של לוח הבקרה

-- ============ קריאות לצוות ============
-- כל בקשה שמאיה ניתבה לצוות. אין כאן תוכן רפואי — רק מה המטופל צריך, מאיפה, ומתי.
create table if not exists public.staff_calls (
  id bigserial primary key,
  user_id uuid references auth.users (id) on delete set null,
  level text not null check (level in ('urgent', 'nurse', 'service', 'info')),
  title text not null,
  note text,
  patient_name text,
  ward text,
  room text,
  status text not null default 'sent' check (status in ('sent', 'ack', 'done')),
  created_at timestamptz not null default now(),
  ack_at timestamptz,
  done_at timestamptz
);

create index if not exists staff_calls_open_idx on public.staff_calls (status, level, created_at desc);
create index if not exists staff_calls_user_idx on public.staff_calls (user_id, created_at desc);

-- חותמות זמן לחישוב זמני תגובה במחלקה
create or replace function public.stamp_call_status()
returns trigger language plpgsql as $$
begin
  if new.status = 'ack' and old.status <> 'ack' and new.ack_at is null then
    new.ack_at := now();
  end if;
  if new.status = 'done' and old.status <> 'done' then
    new.done_at := now();
    if new.ack_at is null then new.ack_at := now(); end if;
  end if;
  return new;
end $$;

drop trigger if exists on_call_status_change on public.staff_calls;
create trigger on_call_status_change
  before update on public.staff_calls
  for each row execute function public.stamp_call_status();

-- ============ יומן שיחה ============
-- תיעוד תפעולי מינימלי: מכסות, איתור באגים ושיפור התסריטים.
create table if not exists public.maya_messages (
  id bigserial primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  role text not null check (role in ('user', 'assistant')),
  content text,
  level text,
  created_at timestamptz not null default now()
);

create index if not exists maya_messages_user_idx on public.maya_messages (user_id, created_at desc);

-- ============ חשבונות צוות ============
-- מי מורשה לראות את כל הקריאות במסך תחנת האחיות.
-- הוספה: יוצרים משתמש ב-Authentication → Users, ואז מריצים
--   insert into public.staff_users (user_id, name) values ('<uuid>', 'שם');
create table if not exists public.staff_users (
  user_id uuid primary key references auth.users (id) on delete cascade,
  name text,
  ward text,
  created_at timestamptz not null default now()
);

create or replace function public.is_staff()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.staff_users s where s.user_id = auth.uid());
$$;

-- ============ אבטחה (RLS) ============
alter table public.staff_calls enable row level security;
alter table public.maya_messages enable row level security;
alter table public.staff_users enable row level security;

-- המטופל רואה רק את הקריאות של עצמו
create policy "patient reads own calls" on public.staff_calls
  for select using (auth.uid() = user_id);

-- הצוות רואה ומעדכן את כל הקריאות
create policy "staff reads all calls" on public.staff_calls
  for select using (public.is_staff());

create policy "staff updates calls" on public.staff_calls
  for update using (public.is_staff()) with check (public.is_staff());

-- המטופל רואה רק את השיחה של עצמו
create policy "patient reads own messages" on public.maya_messages
  for select using (auth.uid() = user_id);

create policy "staff reads own row" on public.staff_users
  for select using (auth.uid() = user_id);

-- הכתיבה של קריאות ושל הודעות נעשית רק דרך פונקציית השרת (service role),
-- כדי שהטאבלט שליד המיטה לא יוכל לזייף קריאות בשם מטופל אחר.
