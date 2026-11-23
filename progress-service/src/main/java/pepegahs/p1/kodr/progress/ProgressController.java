package pepegahs.p1.kodr.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pepegahs.p1.kodr.common.Grading;
import pepegahs.p1.kodr.common.GradingBroker;
import pepegahs.p1.kodr.common.Json;
import pepegahs.p1.kodr.common.KodrWeb;
import pepegahs.p1.kodr.common.Learning.Course;
import pepegahs.p1.kodr.common.Learning.Lesson;
import pepegahs.p1.kodr.common.ServiceCalls.Courses;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/progress")
@PreAuthorize("hasRole('student')")
class ProgressController {
    private final JdbcTemplate db;
    private final Courses courses;
    private final RestClient tasks;

    ProgressController(JdbcTemplate db, Courses courses, @LoadBalanced RestClient.Builder discovery) {
        this.db = db;
        this.courses = courses;
        this.tasks = discovery.clone().baseUrl("http://task-service").build();
    }

    @GetMapping("/courses/{courseId}")
    @Operation(summary = "Получить прогресс по курсу, пройденные уроки и решённые задания")
    Progress get(@PathVariable UUID courseId, @AuthenticationPrincipal Jwt jwt) {
        List<UUID> lessons = lessonIds(courseId, jwt);
        List<UUID> completed = db.queryForList("""
                SELECT lesson_id FROM completed_lessons WHERE student = ? AND course_id = ? ORDER BY completed_at, lesson_id
                """, UUID.class, jwt.getSubject(), courseId);
        List<UUID> solved = db.queryForList("""
                SELECT task_id FROM solved_tasks WHERE student = ? AND course_id = ? ORDER BY accepted_at, task_id
                """, UUID.class, jwt.getSubject(), courseId);
        return new Progress(courseId, lessons.size(), completed, taskCount(courseId, jwt), solved);
    }

    @PutMapping("/courses/{courseId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отметить урок опубликованного курса пройденным")
    void complete(@PathVariable UUID courseId, @PathVariable UUID lessonId, @AuthenticationPrincipal Jwt jwt) {
        if (!lessonIds(courseId, jwt).contains(lessonId)) {
            throw KodrWeb.notFound("Урок не найден в курсе");
        }
        db.update("""
                INSERT INTO completed_lessons (student, course_id, lesson_id) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, jwt.getSubject(), courseId, lessonId);
    }

    private List<UUID> lessonIds(UUID courseId, Jwt jwt) {
        Course course = courses.content(courseId, jwt);
        if (!course.published()) {
            throw KodrWeb.notFound("Опубликованный курс не найден");
        }
        return course.modules().stream().flatMap(module -> module.lessons().stream()).map(Lesson::id).toList();
    }

    private long taskCount(UUID courseId, Jwt jwt) {
        try {
            return KodrWeb.requiredBody(tasks.get().uri("/api/tasks/count?courseId={courseId}", courseId)
                    .headers(headers -> headers.setBearerAuth(jwt.getTokenValue()))
                    .retrieve(), TaskCount.class).total();
        } catch (RestClientException error) {
            throw KodrWeb.unavailable();
        }
    }

    record TaskCount(long total) {
    }

    record Progress(UUID courseId, int totalLessons, List<UUID> completedLessons, long publishedTasks,
                    List<UUID> solvedTasks) {
    }

    @org.springframework.stereotype.Component
    static class AcceptedSubmissions {
        private final JdbcTemplate db;
        private final ObjectMapper mapper;

        AcceptedSubmissions(JdbcTemplate db, ObjectMapper mapper) {
            this.db = db;
            this.mapper = mapper;
        }

        @RabbitListener(queues = GradingBroker.ACCEPTED)
        void accepted(Message message) {
            Grading.Accepted event = Json.read(mapper, new String(message.getBody(), StandardCharsets.UTF_8),
                    Grading.Accepted.class);
            db.update("""
                    INSERT INTO solved_tasks (student, course_id, task_id, accepted_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (student, course_id, task_id) DO UPDATE
                    SET accepted_at = LEAST(solved_tasks.accepted_at, EXCLUDED.accepted_at)
                    """, event.student(), event.courseId(), event.taskId(), Timestamp.from(event.acceptedAt()));
        }
    }
}
