-- Seeds a dedicated load-test merchant so GET /payments/settlement does realistic work
-- (reads and sums 500 rows) without touching real merchants such as MR-4471.
-- Re-runnable: it replaces MR-LOAD's payments with the same deterministic set every time.
--
-- psql -h localhost -U ledger -d ledger -f perf/seed-load-merchant.sql

begin;

delete from payments where merchant_id = 'MR-LOAD';

insert into payments (id, merchant_id, amount_minor, currency, recorded_at)
select gen_random_uuid()::text,
       'MR-LOAD',
       1000 + (i * 37) % 9000,                      -- 10.00 .. 99.99 GBP, deterministic
       'GBP',
       timestamptz '2026-10-01 09:00:00+00' + i * interval '1 minute'
from generate_series(1, 500) as i;

commit;

-- Expected settlement, for checking responses under load.
select count(*)                                  as payments,
       sum(amount_minor)                         as total_minor,
       floor(sum(amount_minor) * 0.031)::bigint  as fee_minor,
       sum(amount_minor) - floor(sum(amount_minor) * 0.031)::bigint as owed_minor
from payments
where merchant_id = 'MR-LOAD';
