create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    email text not null unique,
    full_name text not null,
    roll_number text,
    role text not null check (role in ('teacher', 'student')),
    created_at timestamptz not null default now(),
    face_template text,
    face_enrolled_at timestamptz,
    face_image_base64 text
);

create table if not exists public.sessions (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    teacher_id uuid references public.profiles(id) on delete cascade,
    teacher_name text not null,
    class_date date,
    class_period integer,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    is_active boolean not null default true
);

create table if not exists public.attendance (
    id uuid primary key default gen_random_uuid(),
    student_id uuid references public.profiles(id) on delete cascade,
    student_name text not null,
    student_roll_number text,
    session_id uuid references public.sessions(id) on delete cascade,
    session_code text not null,
    class_date date,
    class_period integer,
    teacher_id uuid references public.profiles(id) on delete cascade,
    teacher_name text,
    marked_at timestamptz not null default now(),
    bluetooth_verified boolean not null default false,
    face_verified boolean not null default false,
    face_match_score double precision,
    bluetooth_rssi integer
);

alter table public.profiles
    add column if not exists email text,
    add column if not exists full_name text,
    add column if not exists roll_number text,
    add column if not exists role text,
    add column if not exists created_at timestamptz not null default now(),
    add column if not exists face_template text,
    add column if not exists face_enrolled_at timestamptz,
    add column if not exists face_image_base64 text;

alter table public.sessions
    add column if not exists teacher_id uuid references public.profiles(id) on delete cascade,
    add column if not exists teacher_name text,
    add column if not exists class_date date,
    add column if not exists class_period integer,
    add column if not exists expires_at timestamptz,
    add column if not exists is_active boolean not null default true;

alter table public.attendance
    add column if not exists student_id uuid references public.profiles(id) on delete cascade,
    add column if not exists student_roll_number text,
    add column if not exists teacher_id uuid references public.profiles(id) on delete cascade,
    add column if not exists teacher_name text,
    add column if not exists session_id uuid references public.sessions(id) on delete cascade,
    add column if not exists session_code text,
    add column if not exists class_date date,
    add column if not exists class_period integer,
    add column if not exists marked_at timestamptz not null default now(),
    add column if not exists bluetooth_verified boolean not null default false,
    add column if not exists face_verified boolean not null default false,
    add column if not exists face_match_score double precision,
    add column if not exists bluetooth_rssi integer;

update public.profiles
set role = 'student'
where role is null or role not in ('teacher', 'student');

alter table public.profiles
    alter column email set not null,
    alter column full_name set not null,
    alter column role set not null;

alter table public.sessions
    alter column teacher_name set not null;

alter table public.attendance
    alter column student_name set not null,
    alter column session_code set not null;

create index if not exists idx_profiles_role on public.profiles(role);
create index if not exists idx_sessions_code on public.sessions(code);
create index if not exists idx_sessions_code_active on public.sessions(code, is_active, expires_at desc);
create index if not exists idx_sessions_teacher_id on public.sessions(teacher_id);
create index if not exists idx_sessions_created_at on public.sessions(created_at desc);
create index if not exists idx_attendance_student_id on public.attendance(student_id);
create index if not exists idx_attendance_teacher_id on public.attendance(teacher_id);
create index if not exists idx_attendance_session_id on public.attendance(session_id);
create index if not exists idx_attendance_marked_at on public.attendance(marked_at desc);
create unique index if not exists idx_attendance_unique_student_session
    on public.attendance(session_id, student_id)
    where student_id is not null;
create unique index if not exists idx_attendance_unique_roll_session
    on public.attendance(session_id, student_roll_number)
    where student_roll_number is not null;
create unique index if not exists idx_attendance_unique_name_session_when_roll_missing
    on public.attendance(session_id, student_name)
    where student_roll_number is null;

alter table public.profiles enable row level security;
alter table public.sessions enable row level security;
alter table public.attendance enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own"
on public.profiles
for select
to authenticated
using (auth.uid() = id);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own"
on public.profiles
for insert
to authenticated
with check (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own"
on public.profiles
for update
to authenticated
using (auth.uid() = id)
with check (auth.uid() = id);

drop policy if exists "sessions_select_authenticated" on public.sessions;
create policy "sessions_select_authenticated"
on public.sessions
for select
to authenticated
using (true);

drop policy if exists "sessions_insert_teacher" on public.sessions;
create policy "sessions_insert_teacher"
on public.sessions
for insert
to authenticated
with check (auth.uid() = teacher_id);

drop policy if exists "sessions_update_teacher" on public.sessions;
create policy "sessions_update_teacher"
on public.sessions
for update
to authenticated
using (auth.uid() = teacher_id)
with check (auth.uid() = teacher_id);

drop policy if exists "attendance_select_related" on public.attendance;
create policy "attendance_select_related"
on public.attendance
for select
to authenticated
using (
    auth.uid() = student_id
    or auth.uid() = teacher_id
    or exists (
        select 1
        from public.sessions s
        where s.id = attendance.session_id
          and s.teacher_id = auth.uid()
    )
);

drop policy if exists "attendance_insert_self" on public.attendance;
create policy "attendance_insert_self"
on public.attendance
for insert
to authenticated
with check (
    auth.uid() = student_id
    and student_id is not null
    and session_id is not null
    and teacher_id is not null
    and session_code is not null
    and bluetooth_verified = true
    and face_verified = true
    and bluetooth_rssi is not null
    and bluetooth_rssi >= -78
    and face_match_score is not null
    and face_match_score >= 0.88
);
