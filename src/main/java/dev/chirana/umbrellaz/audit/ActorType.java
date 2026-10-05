package dev.chirana.umbrellaz.audit;

public enum ActorType {
    PLAYER("player"),
    CONSOLE("console"),
    SYSTEM("system"),
    UNKNOWN("unknown");

    private final String code;

    ActorType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ActorType fromCode(String code) {
        for (ActorType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown audit actor type: " + code);
    }
}
