package dev.chirana.umbrellaz.audit;

public record Target(String type, String id) {
    public static final int MAX_TYPE_LENGTH = 64;
    public static final int MAX_ID_LENGTH = 256;

    public Target {
        AuditValidation.stableCode(type, MAX_TYPE_LENGTH, "Target type");
        AuditValidation.targetIdentifier(id, MAX_ID_LENGTH, "Target id");
    }
}
