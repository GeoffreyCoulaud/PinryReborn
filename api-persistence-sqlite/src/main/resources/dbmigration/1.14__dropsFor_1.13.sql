-- Destructive: `deleted` was the flag Ebean's @SoftDelete maintained, and 1.13 adds
-- `soft_deleted_at` without backfilling it, so an account tombstoned before 1.13 comes out of this
-- pair active and the retention sweep never collects it. No database holds such a row; one that did
-- would run `update users set soft_deleted_at = when_modified where deleted = 1` before this.
-- apply alter tables
alter table users drop column deleted;
