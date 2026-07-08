-- apply changes
create table tasks (
  id                            uuid not null,
  kind                          text not null,
  payload                       text not null,
  state                         text not null,
  priority                      integer not null,
  available_at                  timestamp not null,
  attempts                      integer not null,
  max_attempts                  integer not null,
  lease_id                      text,
  lease_expires_at              timestamp,
  cancel_requested              int default 0 not null,
  dedup_key                     text,
  last_error                    text,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  version                       integer not null,
  constraint pk_tasks primary key (id)
);

-- partial claim index: only runnable rows, ordered for the claim query
create index ix_tasks_claim on tasks (priority desc, available_at asc, id asc) where state = 'PENDING';
-- partial reaper index
create index ix_tasks_lease on tasks (lease_expires_at) where state = 'RUNNING';
-- dedup uniqueness among live tasks only
create unique index ux_tasks_dedup on tasks (dedup_key) where dedup_key is not null and state in ('PENDING','RUNNING');

