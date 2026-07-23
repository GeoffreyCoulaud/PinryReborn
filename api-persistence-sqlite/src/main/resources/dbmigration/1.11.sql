-- foreign keys and indices
create unique index uq_user_data_exports_pending on user_data_exports (user_id) where state = 'PENDING';
