package dev.chirana.umbrellaz.audit;

public record Source(String code) {
    public static final Source COMMAND = new Source("command");
    public static final Source EVENT = new Source("event");
    public static final Source SYSTEM = new Source("system");
    public static final Source AUTH = new Source("auth");
    public static final Source WHITELIST = new Source("whitelist");
    public static final Source LOCK = new Source("lock");
    public static final int MAX_LENGTH = 64;

    public Source {
        AuditValidation.stableCode(code, MAX_LENGTH, "Source");
    }
}
