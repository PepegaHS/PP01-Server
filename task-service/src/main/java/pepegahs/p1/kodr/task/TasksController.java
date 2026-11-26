package pepegahs.p1.kodr.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Language;
import pepegahs.p1.kodr.common.Learning.ChoiceOption;
import pepegahs.p1.kodr.common.Learning.GradingTask;
import pepegahs.p1.kodr.common.Learning.TestCase;
import pepegahs.p1.kodr.common.ServiceCalls.Courses;
import pepegahs.p1.kodr.common.TaskType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
class TasksController {
    private static final TypeReference<Map<String, String>> TEMPLATES = new TypeReference<>() {};
    private static final TypeReference<List<ChoiceOption>> OPTIONS = new TypeReference<>() {};
    private static final TypeReference<Set<String>> ANSWER_KEY = new TypeReference<>() {};
    private static final TypeReference<List<TestCase>> TESTS = new TypeReference<>() {};
    private static final String SUPPORTED_TASK =
            "(task_type = 'QUIZ' OR jsonb_exists(templates, 'JAVA') OR jsonb_exists(templates, 'RUST'))";

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final Courses courses;

    TasksController(JdbcTemplate db, ObjectMapper mapper, Courses courses) {
        this.db = db;
        this.mapper = mapper;
        this.courses = courses;
    }

    @GetMapping("/api/tasks")
    @Operation(summary = "Получить задания курса и свои черновики")
    List<Task> list(@RequestParam UUID courseId, @AuthenticationPrincipal Jwt jwt,
                    @RequestParam(defaultValue = "0") @Min(0) int offset) {
        courses.summary(courseId, jwt);
        return db.query("""
                SELECT * FROM tasks WHERE course_id = ? AND (published OR owner = ?) AND %s
                ORDER BY title, id LIMIT 100 OFFSET ?
                """.formatted(SUPPORTED_TASK), this::task, courseId, jwt.getSubject(), offset);
    }

    @GetMapping("/api/tasks/{id}")
    @Operation(summary = "Получить условие и параметры практического задания или варианты ответа")
    Task get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Task task = db.query("SELECT * FROM tasks WHERE id = ? AND (published OR owner = ?) AND " + SUPPORTED_TASK,
                        this::task, id,
                        jwt.getSubject()).stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
        courses.summary(task.courseId(), jwt);
        return task;
    }

    @GetMapping("/api/tasks/count")
    @Operation(summary = "Получить количество опубликованных заданий курса")
    Count count(@RequestParam UUID courseId, @AuthenticationPrincipal Jwt jwt) {
        courses.summary(courseId, jwt);
        return new Count(db.queryForObject("SELECT count(*) FROM tasks WHERE course_id = ? AND published AND " + SUPPORTED_TASK,
                long.class, courseId));
    }

    @PostMapping("/api/tasks")
    @PreAuthorize("hasRole('teacher')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать черновик практического задания или теста")
    Task create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTask request) {
        if (!courses.summary(request.courseId(), jwt).owner().equals(jwt.getSubject())) {
            throw KodrWeb.notFound("Курс не найден");
        }
        TaskData data = request.data();
        TaskType type = TaskType.defaultToPractical(data.type());
        validate(type, data);
        return db.queryForObject("""
                INSERT INTO tasks (id, course_id, owner, title, description, task_type,
                                   templates, tests, cpu_seconds, memory_kb, options, correct_option_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb)
                RETURNING *
                """, this::task, UUID.randomUUID(), request.courseId(), jwt.getSubject(), data.title(), data.description(),
                type.name(), json(data.templates()), json(data.tests()), data.cpuSeconds(), data.memoryKb(),
                json(data.options()), json(data.correctOptionIds()));
    }

    @GetMapping("/api/tasks/{id}/tests")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Получить скрытые проверки своего практического задания")
    List<TestCase> teacherTests(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return db.query("SELECT tests FROM tasks WHERE id = ? AND owner = ? AND task_type = 'PRACTICAL' AND " + SUPPORTED_TASK,
                        (row, index) -> read(row, "tests", TESTS), id, jwt.getSubject()).stream().findFirst()
                .orElseThrow(() -> KodrWeb.notFound("Практическое задание не найдено"));
    }

    @GetMapping("/api/tasks/{id}/answer-key")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Получить ключ ответов своего тестового задания")
    AnswerKey answerKey(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return db.query("SELECT correct_option_ids FROM tasks WHERE id = ? AND owner = ? AND task_type = 'QUIZ'",
                        (row, index) -> new AnswerKey(read(row, "correct_option_ids", ANSWER_KEY)), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Тестовое задание не найдено"));
    }

    @PutMapping("/api/tasks/{id}")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Обновить практическое или тестовое задание")
    Task update(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TaskData data) {
        TaskType storedType = db.query("SELECT task_type FROM tasks WHERE id = ? AND owner = ? AND " + SUPPORTED_TASK,
                        (row, index) -> TaskType.valueOf(row.getString("task_type")), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
        if (data.type() != null && data.type() != storedType) {
            throw invalid("Тип существующего задания нельзя изменить");
        }
        validate(storedType, data);
        return db.query("""
                UPDATE tasks SET title = ?, description = ?, templates = ?::jsonb, tests = ?::jsonb,
                                cpu_seconds = ?, memory_kb = ?, options = ?::jsonb, correct_option_ids = ?::jsonb
                WHERE id = ? AND owner = ? AND %s RETURNING *
                """.formatted(SUPPORTED_TASK), this::task, data.title(), data.description(), json(data.templates()), json(data.tests()),
                data.cpuSeconds(), data.memoryKb(), json(data.options()), json(data.correctOptionIds()), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
    }

    @PutMapping("/api/tasks/{id}/publication")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Опубликовать задание или вернуть его в черновики")
    Task publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Publication request) {
        return db.query("UPDATE tasks SET published = ? WHERE id = ? AND owner = ? AND " + SUPPORTED_TASK + " RETURNING *",
                        this::task, request.published(), id, jwt.getSubject()).stream().findFirst()
                .orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
    }

    @GetMapping("/internal/tasks/{id}/grading")
    @Operation(summary = "Получить условия проверки решения для Submission Service")
    GradingTask grading(@PathVariable UUID id) {
        return db.query("SELECT * FROM tasks WHERE id = ? AND published AND " + SUPPORTED_TASK, (row, index) -> {
            Task task = task(row, index);
            if (task.type() == TaskType.QUIZ) {
                return new GradingTask(task.id(), task.courseId(), null, null, null, null, task.type(),
                        optionIds(task.options()), read(row, "correct_option_ids", ANSWER_KEY));
            }
            Set<String> languages = task.templates().keySet().stream().map(Language::name).collect(Collectors.toSet());
            return new GradingTask(task.id(), task.courseId(), languages, task.cpuSeconds(), task.memoryKb(),
                    read(row, "tests", TESTS), task.type(), null, null);
        }, id).stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Опубликованное задание не найдено"));
    }

    private Task task(ResultSet row, int index) throws SQLException {
        TaskType type = TaskType.valueOf(row.getString("task_type"));
        Set<String> answerKey = read(row, "correct_option_ids", ANSWER_KEY);
        return new Task(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getString("title"), row.getString("description"),
                supportedTemplates(read(row, "templates", TEMPLATES)),
                row.getObject("cpu_seconds", Double.class), row.getObject("memory_kb", Integer.class),
                row.getBoolean("published"), type, read(row, "options", OPTIONS),
                answerKey == null ? null : answerKey.size() > 1);
    }

    private static Map<Language, String> supportedTemplates(Map<String, String> templates) {
        if (templates == null) {
            return null;
        }
        Map<Language, String> supported = new EnumMap<>(Language.class);
        for (Language language : Language.values()) {
            if (templates.containsKey(language.name())) {
                supported.put(language, templates.get(language.name()));
            }
        }
        return supported;
    }

    private <T> T read(ResultSet row, String column, TypeReference<T> type) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : Json.read(mapper, value, mapper.constructType(type));
    }

    private String json(Object value) {
        return value == null ? null : Json.write(mapper, value);
    }

    private static void validate(TaskType type, TaskData data) {
        boolean hasCode = data.templates() != null || data.tests() != null
                || data.cpuSeconds() != null || data.memoryKb() != null;
        if (type == TaskType.PRACTICAL) {
            if (data.templates() == null || data.tests() == null || data.cpuSeconds() == null || data.memoryKb() == null
                    || data.options() != null || data.correctOptionIds() != null) {
                throw invalid("Для практического задания нужны шаблоны, проверки и лимиты");
            }
            return;
        }
        if (hasCode || data.options() == null || data.correctOptionIds() == null) {
            throw invalid("Для тестового задания нужны варианты и ключ ответов");
        }
        Set<String> ids = optionIds(data.options());
        if (ids.size() != data.options().size() || !ids.containsAll(data.correctOptionIds())) {
            throw invalid("Идентификаторы вариантов должны быть уникальны, а ключ должен ссылаться на варианты");
        }
    }

    private static Set<String> optionIds(List<ChoiceOption> options) {
        Set<String> ids = new LinkedHashSet<>();
        options.forEach(option -> ids.add(option.id()));
        return ids;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Task(UUID id, UUID courseId, String title, String description, Map<Language, String> templates,
                Double cpuSeconds, Integer memoryKb, boolean published, TaskType type,
                List<ChoiceOption> options, Boolean multipleChoice) {
    }

    record TaskData(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 20000) String description,
                    @Size(min = 1) Map<Language, @NotNull @Size(max = 20000) String> templates,
                    @Size(min = 1, max = 10) List<@NotNull @Valid TestCase> tests,
                    @DecimalMin("0.1") @DecimalMax("5") Double cpuSeconds,
                    @Min(32000) @Max(256000) Integer memoryKb, TaskType type,
                    @Size(min = 2, max = 10) List<@NotNull @Valid ChoiceOption> options,
                    @Size(min = 1, max = 10) Set<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> correctOptionIds) {
    }

    record AnswerKey(Set<String> correctOptionIds) {
    }

    record CreateTask(@NotNull UUID courseId, @NotNull @Valid TaskData data) {
    }

    record Publication(@NotNull Boolean published) {
    }

    record Count(long total) {
    }
}
