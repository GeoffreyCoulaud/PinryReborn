-- Destructive: drops the eight Ebean audit columns no code reads (`when_modified` on six tables,
-- plus `when_created` and `when_modified` on tasks and user_data_exports). The tasks sweep moved to
-- `terminal_state_at` (added in 1.15): a task that settled before this pair carries null there and
-- is never collected. No database holds such a row; one that did would run
-- `update tasks set terminal_state_at = <settled-at> where state in ('SUCCEEDED','DEAD','CANCELLED') and terminal_state_at is null` before this.
-- apply alter tables
alter table session_tokens drop column when_modified;
alter table tags drop column when_modified;
alter table tasks drop column when_created;
alter table tasks drop column when_modified;
alter table user_data_exports drop column when_created;
alter table user_data_exports drop column when_modified;
alter table user_password_hashes drop column when_modified;
alter table users drop column when_modified;
