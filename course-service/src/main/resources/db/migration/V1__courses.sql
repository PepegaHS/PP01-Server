CREATE TABLE courses (
    id uuid PRIMARY KEY,
    owner varchar(255) NOT NULL,
    language varchar(10) NOT NULL,
    title varchar(150) NOT NULL,
    description text NOT NULL,
    published boolean NOT NULL DEFAULT false
);
CREATE INDEX courses_owner_idx ON courses (owner);
CREATE INDEX courses_language_published_idx ON courses (language, published);

CREATE TABLE course_modules (
    id uuid PRIMARY KEY,
    course_id uuid NOT NULL REFERENCES courses (id),
    title varchar(150) NOT NULL,
    position integer NOT NULL CHECK (position >= 0)
);
CREATE INDEX course_modules_course_idx ON course_modules (course_id, position);

CREATE TABLE lessons (
    id uuid PRIMARY KEY,
    module_id uuid NOT NULL REFERENCES course_modules (id),
    title varchar(150) NOT NULL,
    content text NOT NULL,
    position integer NOT NULL CHECK (position >= 0)
);
CREATE INDEX lessons_module_idx ON lessons (module_id, position);
