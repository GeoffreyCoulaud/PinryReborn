-- apply changes
-- Hand-written (not generated): Ebean's migration generator has no model-level representation of a
-- partial unique index, so a later `generateDbMigration` run would silently drop this if it lived in
-- an auto-generated file. Enforces "at most one PENDING export per user" at the database level.
create unique index uq_user_data_exports_pending on user_data_exports (user_id) where state = 'PENDING';
