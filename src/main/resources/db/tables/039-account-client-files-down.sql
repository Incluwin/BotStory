-- Rollback for 039-account-client-files.sql: every account's answer is forgotten, so the entry NPCs ask again.
DROP TABLE IF EXISTS account_client_files;
