-- apply changes
create table session_tokens (
  id                            uuid not null,
  user_id                       uuid not null,
  token_hash                    text not null,
  expires_at                    timestamp not null,
  persistent                    int default 0 not null,
  when_created                  timestamp not null,
  when_modified                 timestamp not null,
  constraint uq_session_tokens_token_hash unique (token_hash),
  constraint pk_session_tokens primary key (id),
  foreign key (user_id) references users (id) on delete restrict on update restrict
);

