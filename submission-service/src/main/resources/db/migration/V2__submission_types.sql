ALTER TABLE submissions
    ADD COLUMN task_type varchar(16) NOT NULL DEFAULT 'PRACTICAL',
    ADD COLUMN selected_option_ids jsonb,
    ALTER COLUMN language DROP NOT NULL,
    ALTER COLUMN source_code DROP NOT NULL,
    ADD CONSTRAINT submissions_type_payload CHECK (
        (task_type = 'PRACTICAL' AND language IS NOT NULL AND source_code IS NOT NULL AND selected_option_ids IS NULL)
        OR
        (task_type = 'QUIZ' AND language IS NULL AND source_code IS NULL AND selected_option_ids IS NOT NULL
            AND verdict IS NOT NULL AND finished_at IS NOT NULL)
    );
