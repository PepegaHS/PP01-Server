ALTER TABLE tasks
    ADD COLUMN task_type varchar(16) NOT NULL DEFAULT 'PRACTICAL',
    ADD COLUMN options jsonb,
    ADD COLUMN correct_option_ids jsonb,
    ALTER COLUMN templates DROP NOT NULL,
    ALTER COLUMN tests DROP NOT NULL,
    ALTER COLUMN cpu_seconds DROP NOT NULL,
    ALTER COLUMN memory_kb DROP NOT NULL,
    ADD CONSTRAINT tasks_type_payload CHECK (
        (task_type = 'PRACTICAL' AND templates IS NOT NULL AND tests IS NOT NULL
            AND cpu_seconds IS NOT NULL AND memory_kb IS NOT NULL
            AND options IS NULL AND correct_option_ids IS NULL)
        OR
        (task_type = 'QUIZ' AND options IS NOT NULL AND correct_option_ids IS NOT NULL
            AND templates IS NULL AND tests IS NULL AND cpu_seconds IS NULL AND memory_kb IS NULL)
    );
