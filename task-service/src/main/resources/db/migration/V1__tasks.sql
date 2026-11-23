CREATE TABLE tasks (
    id uuid PRIMARY KEY,
    course_id uuid NOT NULL,
    owner varchar(255) NOT NULL,
    title varchar(150) NOT NULL,
    description text NOT NULL,
    templates jsonb NOT NULL,
    tests jsonb NOT NULL,
    cpu_seconds double precision NOT NULL CHECK (cpu_seconds BETWEEN 0.1 AND 5),
    memory_kb integer NOT NULL CHECK (memory_kb BETWEEN 32000 AND 256000),
    published boolean NOT NULL DEFAULT false
);
CREATE INDEX tasks_course_idx ON tasks (course_id, published);
