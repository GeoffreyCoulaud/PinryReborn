-- foreign keys and indices
create unique index ix_user_password_hashes_user_created on user_password_hashes (user_id, when_created);
