-- No backfill: `terminal_state_at` is nullable, so a task that settled before 1.15 carries null and
-- never satisfies `terminal_state_at < cutoff`, leaving the terminal-task sweep unable to collect it.
-- No database holds such a row; one that did would run
-- `update tasks set terminal_state_at = <settled-at> where state in ('SUCCEEDED','DEAD','CANCELLED') and terminal_state_at is null` before this.
-- drop dependencies
drop index if exists ix_tasks_state_when_modified;
-- apply alter tables
alter table tasks add column terminal_state_at timestamp;
-- foreign keys and indices
create index ix_tasks_state_terminal_state_at on tasks (state,terminal_state_at);
