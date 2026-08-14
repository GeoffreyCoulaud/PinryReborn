-- apply changes
create table user_data_import_issues (
  id                            uuid not null,
  import_id                     uuid not null,
  kind                          text not null,
  line                          integer,
  subject                       text,
  detail                        text,
  constraint pk_user_data_import_issues primary key (id),
  foreign key (import_id) references user_data_imports (id) on delete restrict on update restrict
);

create table user_data_imports (
  id                            uuid not null,
  user_id                       uuid not null,
  state                         text not null,
  requested_at                  timestamp not null,
  task_id                       uuid,
  run_token                     uuid,
  uploaded_bytes                integer not null,
  last_upload_activity_at       timestamp,
  archive_completed_at          timestamp,
  started_at                    timestamp,
  completed_at                  timestamp,
  storage_key                   text,
  byte_size                     integer,
  format_version                integer,
  announced_pins                integer,
  processed_pins                integer not null,
  created_pins                  integer not null,
  skipped_pins                  integer not null,
  created_boards                integer not null,
  skipped_boards                integer not null,
  created_tags                  integer not null,
  skipped_tags                  integer not null,
  issue_count                   integer not null,
  issue_detail_truncated        int default 0 not null,
  failure_code                  text,
  constraint pk_user_data_imports primary key (id),
  foreign key (user_id) references users (id) on delete restrict on update restrict
);

-- foreign keys and indices
create index ix_images_content_hash on images (content_hash);
create index ix_user_data_import_issues_import on user_data_import_issues (import_id);
create unique index uq_user_data_imports_active on user_data_imports (user_id) where state in ('AWAITING_ARCHIVE','PENDING','RUNNING');
create index ix_user_data_imports_user_state on user_data_imports (user_id,state);
