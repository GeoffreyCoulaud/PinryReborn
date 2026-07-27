-- foreign keys and indices
create index ix_session_tokens_expires_at on session_tokens (expires_at);
create index ix_tasks_state_when_modified on tasks (state,when_modified);
