create extension if not exists pgcrypto;

create table if not exists public.sessions (
    id uuid primary key default gen_random_uuid(),
    code text not null,
    teacher_id uuid references auth.users(id) on delete set null,
    teacher_name text not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    is_active boolean not null default true
);

create table if not exists public.attendance (
    id uuid primary key default gen_random_uuid(),
    student_id uuid references auth.users(id) on delete set null,
    student_name text not null,
    student_roll_number text not null,
    session_id uuid not null references public.sessions(id) on delete cascade,
    session_code text not null,
    teacher_id uuid references auth.users(id) on delete set null,
    teacher_name text,
    marked_at timestamptz not null default now(),
    bluetooth_verified boolean not null default false,
    face_verified boolean not null default false,
    face_match_score double precision,
    bluetooth_rssi integer
);

alter table public.sessions
    add column if not exists code text,
    add column if not exists teacher_id uuid references auth.users(id) on delete set null,
    add column if not exists teacher_name text,
    add column if not exists created_at timestamptz default now(),
    add column if not exists expires_at timestamptz,
    add column if not exists is_active boolean default true;

alter table public.attendance
    add column if not exists student_id uuid references auth.users(id) on delete set null,
    add column if not exists student_name text,
    add column if not exists student_roll_number text,
    add column if not exists session_id uuid references public.sessions(id) on delete cascade,
    add column if not exists session_code text,
    add column if not exists teacher_id uuid references auth.users(id) on delete set null,
    add column if not exists teacher_name text,
    add column if not exists marked_at timestamptz default now(),
    add column if not exists bluetooth_verified boolean not null default false,
    add column if not exists face_verified boolean not null default false,
    add column if not exists face_match_score double precision,
    add column if not exists bluetooth_rssi integer;

update public.sessions
set
    teacher_name = coalesce(nullif(teacher_name, ''), 'Teacher'),
    created_at = coalesce(created_at, now()),
    expires_at = coalesce(expires_at, now() + interval '5 minutes'),
    is_active = coalesce(is_active, true);

update public.attendance
set
    student_name = coalesce(nullif(student_name, ''), 'Student'),
    student_roll_number = coalesce(nullif(student_roll_number, ''), 'UNKNOWN'),
    session_code = coalesce(nullif(session_code, ''), 'UNKNOWN'),
    marked_at = coalesce(marked_at, now());

alter table public.sessions
    alter column code set not null,
    alter column teacher_name set not null,
    alter column created_at set not null,
    alter column created_at set default now(),
    alter column expires_at set not null,
    alter column is_active set not null,
    alter column is_active set default true;

alter table public.attendance
    alter column student_name set not null,
    alter column student_roll_number set not null,
    alter column session_id set not null,
    alter column session_code set not null,
    alter column marked_at set not null,
    alter column marked_at set default now();

create index if not exists idx_sessions_active_created on public.sessions(is_active, created_at desc);
create index if not exists idx_sessions_code_lookup on public.sessions(code, is_active, expires_at desc);
create index if not exists idx_attendance_session_lookup on public.attendance(session_id, marked_at desc);
create unique index if not exists idx_attendance_unique_roll_session
    on public.attendance(session_id, student_roll_number);

alter table public.sessions enable row level security;
alter table public.attendance enable row level security;

drop policy if exists "pro_sessions_select_all" on public.sessions;
drop policy if exists "pro_sessions_insert_all" on public.sessions;
drop policy if exists "pro_sessions_update_all" on public.sessions;
drop policy if exists "pro_attendance_select_all" on public.attendance;
drop policy if exists "pro_attendance_insert_all" on public.attendance;
drop policy if exists "sessions_select_authenticated" on public.sessions;
drop policy if exists "sessions_insert_teacher_only" on public.sessions;
drop policy if exists "sessions_update_teacher_only" on public.sessions;
drop policy if exists "attendance_select_related" on public.attendance;
drop policy if exists "attendance_insert_verified_only" on public.attendance;

create policy "sessions_select_authenticated"
on public.sessions
for select
to authenticated
using (teacher_id = auth.uid());

create policy "sessions_insert_teacher_only"
on public.sessions
for insert
to authenticated
with check (teacher_id = auth.uid());

create policy "sessions_update_teacher_only"
on public.sessions
for update
to authenticated
using (teacher_id = auth.uid())
with check (teacher_id = auth.uid());

create policy "attendance_select_related"
on public.attendance
for select
to authenticated
using (
    student_id = auth.uid()
    or teacher_id = auth.uid()
    or exists (
        select 1
        from public.sessions s
        where s.id = attendance.session_id
          and s.teacher_id = auth.uid()
    )
);

create policy "attendance_insert_verified_only"
on public.attendance
for insert
to authenticated
with check (
    student_id = auth.uid()
    and session_id is not null
    and teacher_id is not null
    and session_code is not null
);
