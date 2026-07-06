-- apply changes
create unique index ix_users_name_nocase on users (name collate nocase);
