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
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Language;
import pepegahs.p1.kodr.common.Learning.GradingTask;
import pepegahs.p1.kodr.common.Learning.TestCase;
import pepegahs.p1.kodr.common.ServiceCalls.Courses;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class TasksController {
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
                SELECT * FROM tasks WHERE course_id = ? AND (published OR owner = ?)
                ORDER BY title, id LIMIT 100 OFFSET ?
                """, this::task, courseId, jwt.getSubject(), offset);
    }

    @GetMapping("/api/tasks/{id}")
    @Operation(summary = "Получить условие, языки, шаблоны и ограничения задания")
    Task get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Task task = db.query("SELECT * FROM tasks WHERE id = ? AND (published OR owner = ?)", this::task, id,
                        jwt.getSubject()).stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
        courses.summary(task.courseId(), jwt);
        return task;
    }

    @GetMapping("/api/tasks/count")
    @Operation(summary = "Получить количество опубликованных заданий курса")
    Count count(@RequestParam UUID courseId, @AuthenticationPrincipal Jwt jwt) {
        courses.summary(courseId, jwt);
        return new Count(db.queryForObject("SELECT count(*) FROM tasks WHERE course_id = ? AND published",
                long.class, courseId));
    }

    @PostMapping("/api/tasks")
    @PreAuthorize("hasRole('teacher')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать черновик задания со скрытыми тестами")
    Task create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTask request) {
        if (!courses.summary(request.courseId(), jwt).owner().equals(jwt.getSubject())) {
            throw KodrWeb.notFound("Курс не найден");
        }
        UUID id = UUID.randomUUID();
        TaskData data = request.data();
        db.update("""
                INSERT INTO tasks (id, course_id, owner, title, description, templates, tests, cpu_seconds, memory_kb)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, request.courseId(), jwt.getSubject(), data.title(), data.description(),
                Json.write(mapper, data.templates()), Json.write(mapper, data.tests()), data.cpuSeconds(), data.memoryKb());
        return new Task(id, request.courseId(), data.title(), data.description(), data.templates(),
                data.cpuSeconds(), data.memoryKb(), false);
    }

    @GetMapping("/api/tasks/{id}/tests")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Получить скрытые тесты своего задания")
    List<TestCase> teacherTests(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return db.query("SELECT tests FROM tasks WHERE id = ? AND owner = ?", (row, index) -> tests(row),
                        id, jwt.getSubject()).stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
    }

    @PutMapping("/api/tasks/{id}")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Обновить условие, шаблоны, скрытые тесты и ограничения")
    Task update(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TaskData data) {
        return db.query("""
                UPDATE tasks SET title = ?, description = ?, templates = ?::jsonb, tests = ?::jsonb,
                                cpu_seconds = ?, memory_kb = ?
                WHERE id = ? AND owner = ? RETURNING *
                """, this::task, data.title(), data.description(), Json.write(mapper, data.templates()),
                Json.write(mapper, data.tests()), data.cpuSeconds(), data.memoryKb(), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
    }

    @PutMapping("/api/tasks/{id}/publication")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Опубликовать задание или вернуть его в черновики")
    Task publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Publication request) {
        return db.query("UPDATE tasks SET published = ? WHERE id = ? AND owner = ? RETURNING *",
                        this::task, request.published(), id, jwt.getSubject()).stream().findFirst()
                .orElseThrow(() -> KodrWeb.notFound("Задание не найдено"));
    }

    @GetMapping("/internal/tasks/{id}/grading")
    @Operation(summary = "Получить условия проверки решения для Submission Service")
    GradingTask grading(@PathVariable UUID id) {
        return db.query("SELECT * FROM tasks WHERE id = ? AND published", (row, index) -> {
            Task task = task(row, index);
            return new GradingTask(task.id(), task.courseId(), task.templates().keySet(), task.cpuSeconds(),
                    task.memoryKb(), tests(row));
        }, id).stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Опубликованное задание не найдено"));
    }

    private Task task(ResultSet row, int index) throws SQLException {
        Map<Language, String> templates = Json.read(mapper, row.getString("templates"),
                mapper.constructType(new TypeReference<Map<Language, String>>() {}));
        return new Task(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getString("title"), row.getString("description"), templates, row.getDouble("cpu_seconds"),
                row.getInt("memory_kb"), row.getBoolean("published"));
    }

    private List<TestCase> tests(ResultSet row) throws SQLException {
        return Json.read(mapper, row.getString("tests"), mapper.getTypeFactory().constructCollectionType(List.class, TestCase.class));
    }

    record Task(UUID id, UUID courseId, String title, String description, Map<Language, String> templates,
                double cpuSeconds, int memoryKb, boolean published) {
    }

    record TaskData(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 20000) String description,
                    @NotEmpty Map<Language, @NotNull @Size(max = 20000) String> templates,
                    @NotEmpty @Size(max = 10) List<@NotNull @Valid TestCase> tests,
                    @NotNull @DecimalMin("0.1") @DecimalMax("5") Double cpuSeconds,
                    @NotNull @Min(32000) @Max(256000) Integer memoryKb) {
    }

    record CreateTask(@NotNull UUID courseId, @NotNull @Valid TaskData data) {
    }

    record Publication(@NotNull Boolean published) {
    }

    record Count(long total) {
    }
}
