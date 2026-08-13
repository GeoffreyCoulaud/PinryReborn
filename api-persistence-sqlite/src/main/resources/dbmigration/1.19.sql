-- Both dropped indexes were partial (`1.3.sql:23,25`) and SQLite skips a partial index when the value its
-- predicate tests is a bound parameter, which is how Ebean sends it, so neither served its query.
-- ix_tasks_lease gets no replacement: reapExpired already plans through ix_tasks_state_terminal_state_at,
-- and RUNNING rows are bounded by tasks.worker_count.
-- drop dependencies
drop index if exists ix_tasks_claim;
drop index if exists ix_tasks_lease;
-- foreign keys and indices
create index ix_tasks_claim on tasks (state, priority desc, available_at asc, id asc);
