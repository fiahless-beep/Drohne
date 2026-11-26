-- Skytac: Guthaben je Nutzer und ein Buch über jeden Verbrauch. Eigenes Supabase-Projekt, nichts von GIBS.
-- Die Tabellen sind nur über die Funktionen unten erreichbar (Service-Schlüssel des Servers); Nutzer lesen nichts direkt.

create table if not exists public.skytac_accounts (
  user_id uuid primary key references auth.users(id) on delete cascade,
  credits integer not null default 0 check (credits >= 0),
  plan text not null default 'free',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.skytac_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  delta integer not null,
  kind text not null,
  at timestamptz not null default now()
);
create index if not exists skytac_ledger_user_at on public.skytac_ledger (user_id, at desc);
create index if not exists skytac_ledger_at on public.skytac_ledger (at);

alter table public.skytac_accounts enable row level security;
alter table public.skytac_ledger enable row level security;
-- Keine Policies: anon und authenticated sehen nichts. Der Server nutzt den Service-Schlüssel.

-- Konto lesen; beim ersten Mal anlegen und die Start-Credits einmalig gutschreiben.
create or replace function public.skytac_account(p_user uuid, p_start integer default 40)
returns json language plpgsql security definer set search_path = public as $$
declare a public.skytac_accounts;
begin
  insert into public.skytac_accounts (user_id, credits) values (p_user, greatest(p_start, 0))
    on conflict (user_id) do nothing;
  if found then
    insert into public.skytac_ledger (user_id, delta, kind) values (p_user, greatest(p_start, 0), 'start');
  end if;
  select * into a from public.skytac_accounts where user_id = p_user;
  return json_build_object('credits', a.credits, 'plan', a.plan);
end $$;

-- Abbuchen, bevor Claude gefragt wird. Bricht ab, wenn das Guthaben nicht reicht oder die Tagesgrenze aller Nutzer erreicht ist.
create or replace function public.skytac_spend(p_user uuid, p_cost integer, p_kind text, p_daily_cap integer default 20000)
returns integer language plpgsql security definer set search_path = public as $$
declare left_credits integer; today integer;
begin
  if p_cost <= 0 then raise exception 'bad_cost'; end if;
  perform public.skytac_account(p_user);
  select coalesce(-sum(delta), 0) into today from public.skytac_ledger
    where delta < 0 and at >= date_trunc('day', now());
  if today + p_cost > p_daily_cap then raise exception 'daily_cap'; end if;
  update public.skytac_accounts set credits = credits - p_cost, updated_at = now()
    where user_id = p_user and credits >= p_cost returning credits into left_credits;
  if not found then raise exception 'insufficient_credits'; end if;
  insert into public.skytac_ledger (user_id, delta, kind) values (p_user, -p_cost, p_kind);
  return left_credits;
end $$;

-- Zurückbuchen, wenn Claude nicht geantwortet hat.
create or replace function public.skytac_refund(p_user uuid, p_cost integer, p_kind text)
returns integer language plpgsql security definer set search_path = public as $$
declare left_credits integer;
begin
  update public.skytac_accounts set credits = credits + p_cost, updated_at = now()
    where user_id = p_user returning credits into left_credits;
  insert into public.skytac_ledger (user_id, delta, kind) values (p_user, p_cost, 'refund:' || p_kind);
  return left_credits;
end $$;

revoke all on function public.skytac_account(uuid, integer) from public, anon, authenticated;
revoke all on function public.skytac_spend(uuid, integer, text, integer) from public, anon, authenticated;
revoke all on function public.skytac_refund(uuid, integer, text) from public, anon, authenticated;
grant execute on function public.skytac_account(uuid, integer) to service_role;
grant execute on function public.skytac_spend(uuid, integer, text, integer) to service_role;
grant execute on function public.skytac_refund(uuid, integer, text) to service_role;
