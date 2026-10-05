package dev.chirana.umbrellaz.audit;

public record Outcome(String code) {
    public static final Outcome SUCCESS = new Outcome("success");
    public static final Outcome FAILURE = new Outcome("failure");
    public static final Outcome DENIED = new Outcome("denied");
    public static final Outcome CANCELLED = new Outcome("cancelled");
    public static final int MAX_LENGTH = 64;

    public Outcome {
        AuditValidation.stableCode(code, MAX_LENGTH, "Outcome");
    }
}
