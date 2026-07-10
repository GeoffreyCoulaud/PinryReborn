-- apply changes
create table image_download (
  pin_id                        uuid not null,
  source_url                    text not null,
  status                        text not null,
  reason_code                   text,
  last_error                    text,
  task_id                       uuid not null,
  requested_at                  timestamp not null,
  updated_at                    timestamp not null,
  constraint pk_image_download primary key (pin_id)
);

