package pepegahs.p1.kodr.common;

public enum TaskType {
    PRACTICAL,
    QUIZ;

    public static TaskType defaultToPractical(TaskType type) {
        return type == null ? PRACTICAL : type;
    }
}
