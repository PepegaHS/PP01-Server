ALTER TABLE auth_accounts ADD COLUMN session_version bigint NOT NULL DEFAULT 0;
