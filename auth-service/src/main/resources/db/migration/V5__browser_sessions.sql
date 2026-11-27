CREATE TABLE auth_browser_sessions (
    token_hash char(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES auth_accounts (id) ON DELETE CASCADE,
    session_version bigint NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX auth_browser_sessions_expires_idx ON auth_browser_sessions (expires_at);
