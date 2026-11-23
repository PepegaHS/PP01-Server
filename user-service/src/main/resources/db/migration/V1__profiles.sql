CREATE TABLE profiles (
    subject varchar(255) PRIMARY KEY,
    display_name varchar(100) NOT NULL,
    bio varchar(2000) NOT NULL,
    avatar_url varchar(2048),
    locale varchar(20) NOT NULL DEFAULT 'ru',
    email_notifications boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
