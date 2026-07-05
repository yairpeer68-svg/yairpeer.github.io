-- צל דיגיטלי: סכמת מסד הנתונים (Supabase / PostgreSQL)

-- פרופיל משתמש: מכסה ומצב פרימיום
create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  scans_used int not null default 0,
  scans_limit int not null default 1,          -- סריקה מלאה חינם אחת; שאר הממצאים בפרימיום
  is_premium boolean not null default false,
  created_at timestamptz not null default now()
);

-- מנויי ניטור: כתובות שנבדקות מחדש אוטומטית (מוצפנות בצד השרת)
create table if not exists public.monitors (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  email_hash text not null,                     -- SHA-256 של המייל, לא המייל עצמו
  last_breach_count int not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (user_id, email_hash)
);

create index if not exists monitors_user_idx on public.monitors (user_id);

-- יצירת פרופיל אוטומטית
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

-- Row Level Security
alter table public.profiles enable row level security;
alter table public.monitors enable row level security;

create policy "read own profile" on public.profiles for select using (auth.uid() = id);
create policy "read own monitors" on public.monitors for select using (auth.uid() = user_id);
create policy "manage own monitors" on public.monitors for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
