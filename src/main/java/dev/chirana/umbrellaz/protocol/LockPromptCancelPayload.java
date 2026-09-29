package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.UUID;

public record LockPromptCancelPayload(UUID contextToken) implements CustomPacketPayload {
    public static final Type<LockPromptCancelPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "lock_prompt_cancel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LockPromptCancelPayload> CODEC =
            StreamCodec.of(LockPromptCancelPayload::write, LockPromptCancelPayload::read);

    public LockPromptCancelPayload {
        Objects.requireNonNull(contextToken, "contextToken");
    }

    @Override
    public Type<LockPromptCancelPayload> type() {
        return TYPE;
    }

    private static void write(RegistryFriendlyByteBuf buffer, LockPromptCancelPayload payload) {
        LockPromptPayloadCodec.writeUuid(buffer, payload.contextToken());
    }

    private static LockPromptCancelPayload read(RegistryFriendlyByteBuf buffer) {
        return new LockPromptCancelPayload(LockPromptPayloadCodec.readUuid(buffer));
    }
}
