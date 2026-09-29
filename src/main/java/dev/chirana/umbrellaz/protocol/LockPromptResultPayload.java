package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record LockPromptResultPayload(
        UUID contextToken,
        LockPromptResultCode resultCode,
        int cooldownSeconds,
        String message,
        UUID retryContextToken
) implements CustomPacketPayload {
    public static final Type<LockPromptResultPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "lock_prompt_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockPromptResultPayload> CODEC =
            StreamCodec.of(LockPromptResultPayload::write, LockPromptResultPayload::read);

    public LockPromptResultPayload {
        Objects.requireNonNull(contextToken, "contextToken");
        Objects.requireNonNull(resultCode, "resultCode");
        Objects.requireNonNull(message, "message");
        if (cooldownSeconds < 0 || cooldownSeconds > ProtocolConstants.MAX_LOCK_PROMPT_COOLDOWN_SECONDS) {
            throw new IllegalArgumentException("Cooldown exceeds protocol bound");
        }
        if (message.length() > ProtocolConstants.MAX_LOCK_PROMPT_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message exceeds protocol bound");
        }
        if (resultCode == LockPromptResultCode.COOLDOWN && cooldownSeconds <= 0) {
            throw new IllegalArgumentException("Cooldown result requires a positive cooldown");
        }
        if (resultCode != LockPromptResultCode.COOLDOWN && cooldownSeconds != 0) {
            throw new IllegalArgumentException("Only cooldown results may include cooldown seconds");
        }
        if (resultCode == LockPromptResultCode.ERROR || resultCode == LockPromptResultCode.COOLDOWN) {
            if (retryContextToken == null || contextToken.equals(retryContextToken)) {
                throw new IllegalArgumentException("Retryable result requires a distinct retry context token");
            }
        } else if (retryContextToken != null) {
            throw new IllegalArgumentException("Non-retryable result cannot include a retry context token");
        }
    }

    public LockPromptResultPayload(UUID contextToken, LockPromptResultCode resultCode,
                                   int cooldownSeconds, String message) {
        this(contextToken, resultCode, cooldownSeconds, message, null);
    }

    @Override
    public Type<LockPromptResultPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, LockPromptResultPayload payload) {
        LockPromptPayloadCodec.writeUuid(buffer, payload.contextToken());
        LockPromptPayloadCodec.writeEnum(buffer, payload.resultCode().wireId());
        buffer.writeVarInt(payload.cooldownSeconds());
        LockPromptPayloadCodec.writeBoundedString(buffer, payload.message(),
                ProtocolConstants.MAX_LOCK_PROMPT_MESSAGE_LENGTH);
        buffer.writeBoolean(payload.retryContextToken() != null);
        if (payload.retryContextToken() != null) {
            LockPromptPayloadCodec.writeUuid(buffer, payload.retryContextToken());
        }
    }

    private static LockPromptResultPayload read(RegistryFriendlyByteBuf buffer) {
        UUID contextToken = LockPromptPayloadCodec.readUuid(buffer);
        LockPromptResultCode resultCode = LockPromptPayloadCodec.readEnum(
                buffer, LockPromptResultCode::fromWireId);
        int cooldownSeconds = LockPromptPayloadCodec.readBoundedVarInt(buffer, 0,
                ProtocolConstants.MAX_LOCK_PROMPT_COOLDOWN_SECONDS);
        String message = LockPromptPayloadCodec.readBoundedString(buffer,
                ProtocolConstants.MAX_LOCK_PROMPT_MESSAGE_LENGTH);
        int retryPresent = buffer.readUnsignedByte();
        if (retryPresent > 1) {
            throw new IllegalArgumentException("Invalid retry context token marker");
        }
        UUID retryContextToken = retryPresent == 1
                ? LockPromptPayloadCodec.readUuid(buffer)
                : null;
        return new LockPromptResultPayload(contextToken, resultCode, cooldownSeconds, message,
                retryContextToken);
    }
}
