CREATE TABLE auth_accounts (
    id uuid PRIMARY KEY,
    username varchar(255) NOT NULL CHECK (username = lower(btrim(username)) AND username <> ''),
    email varchar(254) NOT NULL CHECK (email = lower(btrim(email)) AND email <> ''),
    password_hash varchar(512) NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
    role varchar(16) NOT NULL DEFAULT 'student' CHECK (role IN ('student', 'teacher', 'admin')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (username),
    UNIQUE (email),
    CONSTRAINT auth_admin_email_domain CHECK (role <> 'admin' OR email LIKE '%@kodr.native')
);

CREATE TABLE auth_signing_keys (
    name varchar(32) PRIMARY KEY,
    jwk text NOT NULL
);
