CREATE TABLE completed_lessons (
    student varchar(255) NOT NULL,
    course_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student, course_id, lesson_id)
);

CREATE TABLE solved_tasks (
    student varchar(255) NOT NULL,
    course_id uuid NOT NULL,
    task_id uuid NOT NULL,
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY (student, course_id, task_id)
);
