package dev.chirana.umbrellaz.protocol;

public enum LockPromptResultCode {
    SUCCESS(0),
    ERROR(1),
    COOLDOWN(2),
    INVALIDATED(3);

    private final int wireId;

    LockPromptResultCode(int wireId) {
        this.wireId = wireId;
    }

    int wireId() {
        return wireId;
    }

    static LockPromptResultCode fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> SUCCESS;
            case 1 -> ERROR;
            case 2 -> COOLDOWN;
            case 3 -> INVALIDATED;
            default -> throw new IllegalArgumentException("Unknown lock prompt result code");
        };
    }
}
