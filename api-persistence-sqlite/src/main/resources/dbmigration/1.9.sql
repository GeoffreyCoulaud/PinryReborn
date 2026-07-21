-- apply alter tables
alter table users add column deleted int default 0 not null;
