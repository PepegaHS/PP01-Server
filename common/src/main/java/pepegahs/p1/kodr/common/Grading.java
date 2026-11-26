package pepegahs.p1.kodr.common;

import java.time.Instant;
import java.util.UUID;

public final class Grading {
    private Grading() {
    }

    public enum Verdict {
        ACCEPTED, WRONG_ANSWER, COMPILATION_ERROR, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, SYSTEM_ERROR;

        public static Verdict fromJudge0(int status) {
            return switch (status) {
                case 3 -> ACCEPTED;
                case 4 -> WRONG_ANSWER;
                case 5 -> TIME_LIMIT_EXCEEDED;
                case 6 -> COMPILATION_ERROR;
                case 7, 8, 9, 10, 11, 12, 14 -> RUNTIME_ERROR;
                default -> SYSTEM_ERROR;
            };
        }
    }

    public record Requested(UUID id, String language, String sourceCode, Learning.GradingTask task) {
    }

    public record Finished(UUID id, Verdict verdict, int passedTests, int totalTests,
                           Double maxTimeSeconds, Integer maxMemoryKb, String compileOutput) {
    }

    public record Accepted(String student, UUID courseId, UUID taskId, Instant acceptedAt) {
    }
}
