ALTER TABLE profiles
    ADD COLUMN avatar_image text,
    ADD COLUMN avatar_x integer NOT NULL DEFAULT 50 CHECK (avatar_x BETWEEN 0 AND 100),
    ADD COLUMN avatar_y integer NOT NULL DEFAULT 50 CHECK (avatar_y BETWEEN 0 AND 100),
    ADD COLUMN avatar_zoom integer NOT NULL DEFAULT 100 CHECK (avatar_zoom BETWEEN 100 AND 400),
    ADD CONSTRAINT profiles_avatar_single_source CHECK (avatar_url IS NULL OR avatar_image IS NULL);
