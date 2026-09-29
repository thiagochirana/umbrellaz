package dev.chirana.umbrellaz.protocol;

public enum LockPromptKind {
    CREATE(0),
    CONFIRM(1);

    private final int wireId;

    LockPromptKind(int wireId) {
        this.wireId = wireId;
    }

    int wireId() {
        return wireId;
    }

    static LockPromptKind fromWireId(int wireId) {
        return switch (wireId) {
            case 0 -> CREATE;
            case 1 -> CONFIRM;
            default -> throw new IllegalArgumentException("Unknown lock prompt kind");
        };
    }
}
