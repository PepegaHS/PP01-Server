package pepegahs.p1.kodr.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    public record ChoiceOption(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
                               @NotBlank @Size(max = 500) String text) {
    }

    public record GradingTask(UUID id, UUID courseId, Set<String> languages, Double cpuSeconds,
                              Integer memoryKb, List<TestCase> tests, TaskType type,
                              Set<String> optionIds, Set<String> correctOptionIds) {
        public GradingTask {
            type = TaskType.defaultToPractical(type);
        }
    }
}
