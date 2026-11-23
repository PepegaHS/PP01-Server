CREATE TABLE submissions (
    id uuid PRIMARY KEY,
    student varchar(255) NOT NULL,
    course_id uuid NOT NULL,
    task_id uuid NOT NULL,
    language varchar(10) NOT NULL,
    source_code text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    verdict varchar(32),
    passed_tests integer,
    total_tests integer NOT NULL,
    max_time_seconds double precision,
    max_memory_kb integer,
    compile_output text
);
CREATE INDEX submissions_student_idx ON submissions (student, created_at DESC, id);

CREATE TABLE outbox (
    id uuid PRIMARY KEY,
    route varchar(100) NOT NULL,
    payload text NOT NULL,
    trace_headers text NOT NULL,
    next_attempt timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox (next_attempt, id) WHERE sent_at IS NULL;
