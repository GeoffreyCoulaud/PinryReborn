-- apply changes
create table boards (
  id                            uuid not null,
  author_id                     uuid not null,
  name                          text not null,
  description                   text not null,
  soft_deleted_at               timestamp,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  constraint pk_boards primary key (id),
  foreign key (author_id) references users (id) on delete restrict on update restrict
);

create table pin_board_model (
  pin_id                        uuid not null,
  board_id                      uuid not null,
  foreign key (pin_id) references pins (id) on delete restrict on update restrict,
  foreign key (board_id) references boards (id) on delete restrict on update restrict
);

