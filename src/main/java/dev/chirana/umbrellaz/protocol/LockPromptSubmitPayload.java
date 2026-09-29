package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record LockPromptSubmitPayload(UUID contextToken, String password)
        implements CustomPacketPayload {
    public static final Type<LockPromptSubmitPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "lock_prompt_submit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockPromptSubmitPayload> CODEC =
            StreamCodec.of(LockPromptSubmitPayload::write, LockPromptSubmitPayload::read);

    public LockPromptSubmitPayload {
        Objects.requireNonNull(contextToken, "contextToken");
        Objects.requireNonNull(password, "password");
        if (password.length() > ProtocolConstants.MAX_LOCK_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password exceeds transport bound");
        }
    }

    @Override
    public Type<LockPromptSubmitPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, LockPromptSubmitPayload payload) {
        LockPromptPayloadCodec.writeUuid(buffer, payload.contextToken());
        LockPromptPayloadCodec.writeBoundedString(buffer, payload.password(),
                ProtocolConstants.MAX_LOCK_PASSWORD_LENGTH);
    }

    private static LockPromptSubmitPayload read(RegistryFriendlyByteBuf buffer) {
        UUID contextToken = LockPromptPayloadCodec.readUuid(buffer);
        String password = LockPromptPayloadCodec.readBoundedString(buffer,
                ProtocolConstants.MAX_LOCK_PASSWORD_LENGTH);
        return new LockPromptSubmitPayload(contextToken, password);
    }
}
