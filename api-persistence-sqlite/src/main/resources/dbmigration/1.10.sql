-- apply changes
create table user_data_exports (
  id                            uuid not null,
  user_id                       uuid not null,
  state                         text not null,
  format_version                integer not null,
  requested_at                  timestamp not null,
  task_id                       uuid,
  completed_at                  timestamp,
  expires_at                    timestamp,
  storage_key                   text,
  byte_size                     integer,
  sha256                        text,
  media_type                    text,
  file_extension                text,
  failure_code                  text,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  constraint pk_user_data_exports primary key (id),
  foreign key (user_id) references users (id) on delete restrict on update restrict
);

