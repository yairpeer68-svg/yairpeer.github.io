-- פקיד: סכמת מסד הנתונים (Supabase / PostgreSQL)
-- מריצים פעם אחת ב-SQL Editor של לוח הבקרה

-- פרופיל לכל משתמש: מכסה חינמית ומצב פרימיום
create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  scans_used int not null default 0,
  scans_limit int not null default 3,          -- מכסה חודשית בחינם
  is_premium boolean not null default false,
  period_start date not null default date_trunc('month', now())::date,
  created_at timestamptz not null default now()
);

-- ניתוחי מכתבים
create table if not exists public.scans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  analysis jsonb not null,
  created_at timestamptz not null default now()
);

create index if not exists scans_user_idx on public.scans (user_id, created_at desc);

-- יצירת פרופיל אוטומטית לכל משתמש חדש
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id) values (new.id) on conflict do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- אבטחה: כל משתמש רואה רק את הנתונים של עצמו
alter table public.profiles enable row level security;
alter table public.scans enable row level security;

create policy "read own profile" on public.profiles
  for select using (auth.uid() = id);

create policy "read own scans" on public.scans
  for select using (auth.uid() = user_id);

create policy "delete own scans" on public.scans
  for delete using (auth.uid() = user_id);

-- הכתיבה נעשית רק דרך פונקציית השרת (service role), לא ישירות מהלקוח
