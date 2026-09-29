package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record LockPromptOpenPayload(UUID contextToken, LockPromptKind promptKind)
        implements CustomPacketPayload {
    public static final Type<LockPromptOpenPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "lock_prompt_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockPromptOpenPayload> CODEC =
            StreamCodec.of(LockPromptOpenPayload::write, LockPromptOpenPayload::read);

    public LockPromptOpenPayload {
        Objects.requireNonNull(contextToken, "contextToken");
        Objects.requireNonNull(promptKind, "promptKind");
    }

    @Override
    public Type<LockPromptOpenPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, LockPromptOpenPayload payload) {
        LockPromptPayloadCodec.writeUuid(buffer, payload.contextToken());
        LockPromptPayloadCodec.writeEnum(buffer, payload.promptKind().wireId());
    }

    private static LockPromptOpenPayload read(RegistryFriendlyByteBuf buffer) {
        UUID contextToken = LockPromptPayloadCodec.readUuid(buffer);
        LockPromptKind promptKind = LockPromptPayloadCodec.readEnum(buffer, LockPromptKind::fromWireId);
        return new LockPromptOpenPayload(contextToken, promptKind);
    }
}
