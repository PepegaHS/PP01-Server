package pepegahs.p1.kodr.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pepegahs.p1.kodr.common.Grading;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Language;
import pepegahs.p1.kodr.common.Learning.GradingTask;
import pepegahs.p1.kodr.common.ServiceCalls.Courses;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/submissions")
class SubmissionsController {
    private final GradingTasks tasks;
    private final Courses courses;
    private final Submissions store;

    SubmissionsController(GradingTasks tasks, Courses courses, Submissions store) {
        this.tasks = tasks;
        this.courses = courses;
        this.store = store;
    }

    @PostMapping
    @PreAuthorize("hasRole('student')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Сохранить решение и поставить его в очередь проверки")
    Submission submit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubmissionData data) {
        GradingTask task = tasks.get(data.taskId());
        if (!courses.summary(task.courseId(), jwt).published()) {
            throw KodrWeb.notFound("Опубликованный курс не найден");
        }
        if (!task.languages().contains(data.language())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Язык не поддерживается заданием");
        }
        return store.create(jwt.getSubject(), data, task);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить исходный код, статус и вердикт своего решения")
    Submission get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return store.get(id, jwt.getSubject());
    }

    @GetMapping
    @Operation(summary = "Получить историю своих решений")
    List<Submission> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return store.list(jwt.getSubject(), offset);
    }

    record SubmissionData(@NotNull UUID taskId, @NotNull Language language, @NotBlank @Size(max = 65536) String sourceCode) {
    }

    record Submission(UUID id, UUID courseId, UUID taskId, Language language, String sourceCode, Instant createdAt,
                      Instant finishedAt, String status, Grading.Verdict verdict, Integer passedTests, int totalTests,
                      Double maxTimeSeconds, Integer maxMemoryKb, String compileOutput) {
    }

    @Service
    static class Submissions {
        private static final Logger LOG = LoggerFactory.getLogger(Submissions.class);
        private static final RowMapper<Submission> SUBMISSION = (row, index) -> {
            String verdict = row.getString("verdict");
            Timestamp finished = row.getTimestamp("finished_at");
            return new Submission(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                    row.getObject("task_id", UUID.class), Language.valueOf(row.getString("language")),
                    row.getString("source_code"), row.getTimestamp("created_at").toInstant(),
                    finished == null ? null : finished.toInstant(), verdict == null ? "QUEUED" : "COMPLETED",
                    verdict == null ? null : Grading.Verdict.valueOf(verdict), row.getObject("passed_tests", Integer.class),
                    row.getInt("total_tests"), row.getObject("max_time_seconds", Double.class),
                    row.getObject("max_memory_kb", Integer.class), row.getString("compile_output"));
        };

        private final JdbcTemplate db;
        private final ObjectMapper mapper;
        private final Outbox outbox;

        Submissions(JdbcTemplate db, ObjectMapper mapper, Outbox outbox) {
            this.db = db;
            this.mapper = mapper;
            this.outbox = outbox;
        }

        @Transactional
        public Submission create(String student, SubmissionData data, GradingTask task) {
            UUID id = UUID.randomUUID();
            Submission submission = db.queryForObject("""
                    INSERT INTO submissions (id, student, course_id, task_id, language, source_code, total_tests)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                    """, SUBMISSION, id, student, task.courseId(), task.id(), data.language().name(),
                    data.sourceCode(), task.tests().size());
            outbox.add(GradingBroker.REQUESTED, new Grading.Requested(id, data.language(), data.sourceCode(), task));
            LOG.info("Queued submission {}", id);
            return submission;
        }

        Submission get(UUID id, String student) {
            return db.query("SELECT * FROM submissions WHERE id = ? AND student = ?", SUBMISSION, id, student)
                    .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Решение не найдено"));
        }

        List<Submission> list(String student, int offset) {
            return db.query("SELECT * FROM submissions WHERE student = ? ORDER BY created_at DESC, id LIMIT 50 OFFSET ?",
                    SUBMISSION, student, offset);
        }

        @RabbitListener(queues = GradingBroker.FINISHED)
        @Transactional
        public void finish(Message message) {
            Grading.Finished event = Json.read(mapper, new String(message.getBody(), StandardCharsets.UTF_8),
                    Grading.Finished.class);
            List<Grading.Accepted> accepted = db.query("""
                    UPDATE submissions SET verdict = ?, finished_at = now(), passed_tests = ?,
                            max_time_seconds = ?, max_memory_kb = ?, compile_output = ?
                    WHERE id = ? AND verdict IS NULL
                    RETURNING student, course_id, task_id, finished_at
                    """, (row, index) -> new Grading.Accepted(row.getString("student"),
                    row.getObject("course_id", UUID.class), row.getObject("task_id", UUID.class),
                    row.getTimestamp("finished_at").toInstant()), event.verdict().name(), event.passedTests(),
                    event.maxTimeSeconds(), event.maxMemoryKb(), event.compileOutput(), event.id());
            if (!accepted.isEmpty()) {
                if (event.verdict() == Grading.Verdict.ACCEPTED) {
                    outbox.add(GradingBroker.ACCEPTED, accepted.getFirst());
                }
                LOG.info("Graded submission {}: {}", event.id(), event.verdict());
            }
        }
    }
}
