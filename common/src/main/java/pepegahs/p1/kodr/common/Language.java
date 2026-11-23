package pepegahs.p1.kodr.common;

public enum Language {
    PYTHON(71, "Python"),
    JAVA(62, "Java"),
    C(50, "C"),
    CPP(54, "C++"),
    CSHARP(51, "C#"),
    PHP(68, "PHP");

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
