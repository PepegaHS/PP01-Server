package pepegahs.p1.kodr.course;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Language;
import pepegahs.p1.kodr.common.Learning.Course;
import pepegahs.p1.kodr.common.Learning.Lesson;
import pepegahs.p1.kodr.common.Learning.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/courses")
class CoursesController {
    private static final RowMapper<CourseSummary> SUMMARY = (row, index) -> new CourseSummary(
            row.getObject("id", UUID.class), row.getString("owner"), Language.valueOf(row.getString("language")),
            row.getString("title"), row.getString("description"), row.getBoolean("published"));
    private final JdbcTemplate db;
    private final CourseContent content;

    CoursesController(JdbcTemplate db, CourseContent content) {
        this.db = db;
        this.content = content;
    }

    @GetMapping
    @Operation(summary = "Получить опубликованные курсы и свои черновики")
    List<CourseSummary> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Language language,
                             @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int offset) {
        String sql = "SELECT * FROM courses WHERE language IN ('JAVA', 'RUST') AND (published OR owner = ?)";
        if (language != null) {
            return db.query(sql + " AND language = ? ORDER BY title, id LIMIT 100 OFFSET ?", SUMMARY,
                    jwt.getSubject(), language.name(), offset);
        }
        return db.query(sql + " ORDER BY title, id LIMIT 100 OFFSET ?", SUMMARY, jwt.getSubject(), offset);
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "Получить сведения о курсе без учебных материалов")
    CourseSummary summary(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return db.query("SELECT * FROM courses WHERE id = ? AND language IN ('JAVA', 'RUST') AND (published OR owner = ?)", SUMMARY, id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Курс не найден"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить курс, его модули и уроки")
    Course get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return content.get(id, jwt.getSubject());
    }

    @PostMapping
    @PreAuthorize("hasRole('teacher')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать черновик курса")
    CourseSummary create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CourseData data) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO courses (id, owner, language, title, description) VALUES (?, ?, ?, ?, ?)",
                id, jwt.getSubject(), data.language().name(), data.title(), data.description());
        return new CourseSummary(id, jwt.getSubject(), data.language(), data.title(), data.description(), false);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Изменить информацию о своём курсе")
    CourseSummary update(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CourseData data) {
        return db.query("""
                UPDATE courses SET language = ?, title = ?, description = ?
                WHERE id = ? AND owner = ? AND language IN ('JAVA', 'RUST') RETURNING *
                """, SUMMARY, data.language().name(), data.title(), data.description(), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Курс не найден"));
    }

    @PutMapping("/{id}/publication")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Опубликовать курс с материалами или вернуть его в черновики")
    CourseSummary publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Publication data) {
        return db.query("UPDATE courses SET published = ? WHERE id = ? AND owner = ? AND language IN ('JAVA', 'RUST') RETURNING *",
                        SUMMARY, data.published(), id, jwt.getSubject())
                .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Курс не найден"));
    }

    @PostMapping("/{id}/modules")
    @PreAuthorize("hasRole('teacher')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить модуль в свой курс")
    Module addModule(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ModuleData data) {
        UUID module = UUID.randomUUID();
        int rows = db.update("""
                INSERT INTO course_modules (id, course_id, title, position)
                SELECT ?, id, ?, ? FROM courses WHERE id = ? AND owner = ? AND language IN ('JAVA', 'RUST')
                """, module, data.title(), data.position(), id, jwt.getSubject());
        if (rows == 0) {
            throw KodrWeb.notFound("Курс не найден");
        }
        return new Module(module, data.title(), data.position(), List.of());
    }

    @PutMapping("/{id}/modules/{module}")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Изменить название и порядок модуля")
    Module updateModule(@PathVariable UUID id, @PathVariable UUID module, @AuthenticationPrincipal Jwt jwt,
                        @Valid @RequestBody ModuleData data) {
        return content.updateModule(id, module, jwt.getSubject(), data);
    }

    @PostMapping("/{id}/modules/{module}/lessons")
    @PreAuthorize("hasRole('teacher')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить урок в модуль своего курса")
    Lesson addLesson(@PathVariable UUID id, @PathVariable UUID module, @AuthenticationPrincipal Jwt jwt,
                     @Valid @RequestBody LessonData data) {
        UUID lesson = UUID.randomUUID();
        int rows = db.update("""
                INSERT INTO lessons (id, module_id, title, content, position)
                SELECT ?, m.id, ?, ?, ? FROM course_modules m JOIN courses c ON c.id = m.course_id
                WHERE m.id = ? AND c.id = ? AND c.owner = ? AND c.language IN ('JAVA', 'RUST')
                """, lesson, data.title(), data.content(), data.position(), module, id, jwt.getSubject());
        return lessonResult(lesson, data, rows);
    }

    @PutMapping("/{id}/modules/{module}/lessons/{lesson}")
    @PreAuthorize("hasRole('teacher')")
    @Operation(summary = "Изменить материал и порядок урока в модуле")
    Lesson updateLesson(@PathVariable UUID id, @PathVariable UUID module, @PathVariable UUID lesson,
                        @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LessonData data) {
        int rows = db.update("""
                UPDATE lessons l SET title = ?, content = ?, position = ?
                FROM course_modules m JOIN courses c ON c.id = m.course_id
                WHERE l.id = ? AND l.module_id = m.id AND m.id = ? AND c.id = ? AND c.owner = ? AND c.language IN ('JAVA', 'RUST')
                """, data.title(), data.content(), data.position(), lesson, module, id, jwt.getSubject());
        return lessonResult(lesson, data, rows);
    }

    private Lesson lessonResult(UUID lesson, LessonData data, int rows) {
        if (rows == 0) {
            throw KodrWeb.notFound("Модуль или урок не найден");
        }
        return new Lesson(lesson, data.title(), data.content(), data.position());
    }

    record CourseSummary(UUID id, String owner, Language language, String title, String description, boolean published) {
    }

    record CourseData(@NotNull Language language, @NotBlank @Size(max = 150) String title,
                      @NotNull @Size(max = 10000) String description) {
    }

    record Publication(@NotNull Boolean published) {
    }

    record ModuleData(@NotBlank @Size(max = 150) String title, @PositiveOrZero int position) {
    }

    record LessonData(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 200000) String content,
                      @PositiveOrZero int position) {
    }

    @Service
    static class CourseContent {
        private static final RowMapper<Lesson> LESSON = (row, index) -> new Lesson(row.getObject("id", UUID.class),
                row.getString("title"), row.getString("content"), row.getInt("position"));
        private final JdbcTemplate db;

        CourseContent(JdbcTemplate db) {
            this.db = db;
        }

        @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
        public Course get(UUID id, String subject) {
            CourseSummary course = db.query("SELECT * FROM courses WHERE id = ? AND language IN ('JAVA', 'RUST') AND (published OR owner = ?)",
                            SUMMARY, id, subject)
                    .stream().findFirst().orElseThrow(() -> KodrWeb.notFound("Курс не найден"));
            List<Module> modules = db.query("""
                    SELECT m.id AS module_id, m.title AS module_title, m.position AS module_position,
                           l.id, l.title, l.content, l.position
                    FROM course_modules m LEFT JOIN lessons l ON l.module_id = m.id
                    WHERE m.course_id = ?
                    ORDER BY m.position, m.id, l.position, l.id
                    """, rows -> {
                List<Module> result = new ArrayList<>();
                Module current = null;
                int index = 0;
                while (rows.next()) {
                    UUID moduleId = rows.getObject("module_id", UUID.class);
                    if (current == null || !current.id().equals(moduleId)) {
                        current = new Module(moduleId, rows.getString("module_title"), rows.getInt("module_position"),
                                new ArrayList<>());
                        result.add(current);
                    }
                    if (rows.getObject("id") != null) {
                        current.lessons().add(LESSON.mapRow(rows, index++));
                    }
                }
                return result;
            }, id);
            return new Course(id, course.owner(), course.language(), course.title(), course.description(),
                    course.published(), modules);
        }

        @Transactional
        public Module updateModule(UUID course, UUID module, String owner, ModuleData data) {
            int rows = db.update("""
                    UPDATE course_modules m SET title = ?, position = ? FROM courses c
                    WHERE m.id = ? AND m.course_id = c.id AND c.id = ? AND c.owner = ? AND c.language IN ('JAVA', 'RUST')
                    """, data.title(), data.position(), module, course, owner);
            if (rows == 0) {
                throw KodrWeb.notFound("Модуль не найден");
            }
            List<Lesson> lessons = db.query("SELECT * FROM lessons WHERE module_id = ? ORDER BY position, id", LESSON, module);
            return new Module(module, data.title(), data.position(), lessons);
        }
    }
}
