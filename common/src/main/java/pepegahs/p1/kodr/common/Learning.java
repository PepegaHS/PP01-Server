package pepegahs.p1.kodr.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Learning {
    private Learning() {
    }

    public record Lesson(UUID id, String title, String content, int position) {
    }

    public record Module(UUID id, String title, int position, List<Lesson> lessons) {
    }

    public record Course(UUID id, String owner, Language language, String title, String description,
                         boolean published, List<Module> modules) {
    }

    public record TestCase(@NotNull @Size(max = 8192) String stdin, @NotNull @Size(max = 8192) String expectedOutput) {
    }

    public record GradingTask(UUID id, UUID courseId, Set<Language> languages, double cpuSeconds,
                              int memoryKb, List<TestCase> tests) {
    }
}
