-- FSRS v4 stability and difficulty cannot be reused by FSRS-6.
delete from public.cards;

alter table public.cards
  add constraint cards_fsrs6_schedule_check
  check (schedule @> '{"fsrs-version":6}'::jsonb
         and schedule ? 'step'
         and jsonb_typeof(schedule -> 'step') in ('number', 'null'));
