package pepegahs.p1.kodr.common;

public enum Language {
    JAVA(62, "Java"),
    RUST(73, "Rust");

    private final int judge0Id;
    private final String displayName;

    Language(int judge0Id, String displayName) {
        this.judge0Id = judge0Id;
        this.displayName = displayName;
    }

    public int judge0Id() {
        return judge0Id;
    }

    public String displayName() {
        return displayName;
    }
}
