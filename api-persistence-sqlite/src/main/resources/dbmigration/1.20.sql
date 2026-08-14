-- foreign keys and indices
create unique index ix_boards_author_name_nocase on boards (author_id, name collate nocase);
create unique index ix_tags_author_name_nocase on tags (author_id, name collate nocase);
