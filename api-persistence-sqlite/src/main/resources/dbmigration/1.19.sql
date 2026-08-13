-- Both were partial (`1.3.sql:23,25`), so SQLite skipped them: Ebean binds the state they test.
-- ix_tasks_lease gets no replacement, reapExpired already planning through ix_tasks_state_terminal_state_at.
-- drop dependencies
drop index if exists ix_tasks_claim;
drop index if exists ix_tasks_lease;
-- foreign keys and indices
create index ix_tasks_claim on tasks (state, priority desc, available_at asc, id asc);
