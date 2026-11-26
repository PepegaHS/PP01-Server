package pepegahs.p1.kodr.submission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
import pepegahs.p1.kodr.common.TaskType;
import pepegahs.p1.kodr.common.Learning.GradingTask;
import pepegahs.p1.kodr.common.ServiceCalls.Courses;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    @Operation(summary = "Поставить код в очередь или проверить выбранные варианты ответа")
    @ApiResponse(responseCode = "202", description = "Код поставлен в очередь")
    @ApiResponse(responseCode = "201", description = "Тест проверен, результат сохранён")
    @ApiResponse(responseCode = "400", description = "Данные решения не подходят заданию")
    ResponseEntity<Submission> submit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubmissionData data) {
        GradingTask task = tasks.get(data.taskId());
        if (!courses.summary(task.courseId(), jwt).published()) {
            throw KodrWeb.notFound("Опубликованный курс не найден");
        }
        if (task.type() == TaskType.QUIZ) {
            Grading.Verdict verdict = gradeQuiz(data, task);
            return ResponseEntity.status(HttpStatus.CREATED).body(store.createQuiz(jwt.getSubject(), data, task, verdict));
        }
        if (data.language() == null || data.sourceCode() == null || data.sourceCode().isBlank()
                || data.selectedOptionIds() != null) {
            throw invalid("Укажите язык и исходный код практического решения");
        }
        if (!task.languages().contains(data.language().name())) {
            throw invalid("Язык не поддерживается заданием");
        }
        return ResponseEntity.accepted().body(store.create(jwt.getSubject(), data, task));
    }

    private Grading.Verdict gradeQuiz(SubmissionData data, GradingTask task) {
        if (data.selectedOptionIds() == null || data.language() != null || data.sourceCode() != null) {
            throw invalid("Для теста укажите только идентификаторы выбранных вариантов");
        }
        Set<String> selected = new HashSet<>(data.selectedOptionIds());
        if (selected.size() != data.selectedOptionIds().size() || !task.optionIds().containsAll(selected)) {
            throw invalid("Выберите существующие варианты без повторений");
        }
        if (task.correctOptionIds().size() == 1 && selected.size() != 1) {
            throw invalid("В этом тесте можно выбрать только один вариант");
        }
        return selected.equals(task.correctOptionIds()) ? Grading.Verdict.ACCEPTED : Grading.Verdict.WRONG_ANSWER;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить данные, статус и вердикт своего решения")
    Submission get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return store.get(id, jwt.getSubject());
    }

    @GetMapping
    @Operation(summary = "Получить историю своих решений")
    List<Submission> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return store.list(jwt.getSubject(), offset);
    }

    record SubmissionData(@NotNull UUID taskId, Language language, @Size(max = 65536) String sourceCode,
                          @Size(min = 1, max = 10) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> selectedOptionIds) {
    }

    record Submission(UUID id, UUID courseId, UUID taskId, String language, String sourceCode, Instant createdAt,
                      Instant finishedAt, String status, Grading.Verdict verdict, Integer passedTests, int totalTests,
                      Double maxTimeSeconds, Integer maxMemoryKb, String compileOutput,
                      TaskType type, List<String> selectedOptionIds) {
    }

    @Service
    static class Submissions {
        private static final Logger LOG = LoggerFactory.getLogger(Submissions.class);
        private static final TypeReference<List<String>> SELECTED_OPTIONS = new TypeReference<>() {};
        private final JdbcTemplate db;
        private final ObjectMapper mapper;
        private final Outbox outbox;

        Submissions(JdbcTemplate db, ObjectMapper mapper, Outbox outbox) {
            this.db = db;
            this.mapper = mapper;
            this.outbox = outbox;
        }

        private Submission submission(ResultSet row, int index) throws SQLException {
            String verdict = row.getString("verdict");
            Timestamp finished = row.getTimestamp("finished_at");
            String selectedOptions = row.getString("selected_option_ids");
            return new Submission(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                    row.getObject("task_id", UUID.class), row.getString("language"),
                    row.getString("source_code"), row.getTimestamp("created_at").toInstant(),
                    finished == null ? null : finished.toInstant(), verdict == null ? "QUEUED" : "COMPLETED",
                    verdict == null ? null : Grading.Verdict.valueOf(verdict), row.getObject("passed_tests", Integer.class),
                    row.getInt("total_tests"), row.getObject("max_time_seconds", Double.class),
                    row.getObject("max_memory_kb", Integer.class), row.getString("compile_output"),
                    TaskType.valueOf(row.getString("task_type")), selectedOptions == null ? null
                            : Json.read(mapper, selectedOptions, mapper.constructType(SELECTED_OPTIONS)));
        }

        @Transactional
        public Submission create(String student, SubmissionData data, GradingTask task) {
            UUID id = UUID.randomUUID();
            Submission submission = db.queryForObject("""
                    INSERT INTO submissions (id, student, course_id, task_id, language, source_code, total_tests)
                    VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                    """, this::submission, id, student, task.courseId(), task.id(), data.language().name(),
                    data.sourceCode(), task.tests().size());
            outbox.add(GradingBroker.REQUESTED, new Grading.Requested(id, data.language().name(), data.sourceCode(), task));
            LOG.info("Queued submission {}", id);
            return submission;
        }

        @Transactional
        public Submission createQuiz(String student, SubmissionData data, GradingTask task, Grading.Verdict verdict) {
            boolean accepted = verdict == Grading.Verdict.ACCEPTED;
            Submission submission = db.queryForObject("""
                    INSERT INTO submissions (id, student, course_id, task_id, task_type, selected_option_ids,
                                             finished_at, verdict, passed_tests, total_tests)
                    VALUES (?, ?, ?, ?, 'QUIZ', ?::jsonb, now(), ?, ?, 1) RETURNING *
                    """, this::submission, UUID.randomUUID(), student, task.courseId(), task.id(),
                    Json.write(mapper, data.selectedOptionIds()), verdict.name(), accepted ? 1 : 0);
            if (accepted) {
                outbox.add(GradingBroker.ACCEPTED,
                        new Grading.Accepted(student, task.courseId(), task.id(), submission.finishedAt()));
            }
            return submission;
        }

        Submission get(UUID id, String student) {
            return db.query("SELECT * FROM submissions WHERE id = ? AND student = ?", this::submission, id, student)
                    .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Решение не найдено"));
        }

        List<Submission> list(String student, int offset) {
            return db.query("SELECT * FROM submissions WHERE student = ? ORDER BY created_at DESC, id LIMIT 50 OFFSET ?",
                    this::submission, student, offset);
        }

        @RabbitListener(queues = GradingBroker.FINISHED)
        @Transactional
        public void finish(Message message) {
            Grading.Finished event = Json.read(mapper, new String(message.getBody(), StandardCharsets.UTF_8),
                    Grading.Finished.class);
            List<Grading.Accepted> accepted = db.query("""
                    UPDATE submissions SET verdict = ?, finished_at = now(), passed_tests = ?,
                            max_time_seconds = ?, max_memory_kb = ?, compile_output = ?
                    WHERE id = ? AND task_type = 'PRACTICAL' AND verdict IS NULL
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
