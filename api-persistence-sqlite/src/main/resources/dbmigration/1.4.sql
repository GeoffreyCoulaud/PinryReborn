-- apply changes
create table images (
  id                            uuid not null,
  pin_id                        uuid not null,
  mime_type                     text not null,
  width                         integer not null,
  height                        integer not null,
  byte_size                     integer not null,
  content_hash                  text not null,
  storage_key                   text not null,
  created_at                    timestamp not null,
  constraint pk_images primary key (id),
  constraint uq_images_pin_id unique (pin_id),
  foreign key (pin_id) references pins (id) on delete restrict on update restrict
);

-- apply alter tables
-- SQLite cannot alter a column's NOT NULL constraint in place (the generator left a
-- "not supported" placeholder here). Rebuild `pins` with `source_media_url` made nullable,
-- preserving every existing column, the primary key, and the `author_id` foreign key.
create table pins_tmp_rebuild (
  id                            uuid not null,
  author_id                     uuid not null,
  source_context_url            text not null,
  source_media_url              text,
  description                   text not null,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  soft_deleted_at               timestamp,
  constraint pk_pins primary key (id),
  foreign key (author_id) references users (id) on delete restrict on update restrict
);
insert into pins_tmp_rebuild (id, author_id, source_context_url, source_media_url, description, when_created, when_modified, soft_deleted_at)
  select id, author_id, source_context_url, source_media_url, description, when_created, when_modified, soft_deleted_at
  from pins;
drop table pins;
alter table pins_tmp_rebuild rename to pins;
