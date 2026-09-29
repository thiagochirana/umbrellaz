package dev.chirana.umbrellaz.protocol;

import java.util.Set;

public final class ProtocolConstants {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_FEATURES = 16;
    public static final int MAX_FEATURE_ID = 1024;
    public static final long HANDSHAKE_DEADLINE_TICKS = 200L;
    public static final int FEATURE_LOCK_GUI = 1;
    public static final int MAX_LOCK_PASSWORD_LENGTH = 4;
    public static final int MAX_LOCK_PROMPT_MESSAGE_LENGTH = 96;
    public static final int MAX_LOCK_PROMPT_COOLDOWN_SECONDS = 30;
    public static final Set<Integer> MANDATORY_FEATURES = Set.of(FEATURE_LOCK_GUI);

    private ProtocolConstants() {
    }
}
