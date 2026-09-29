package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.UUID;
import java.util.function.IntFunction;

final class LockPromptPayloadCodec {
    private LockPromptPayloadCodec() {
    }

    static void writeUuid(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits());
        buffer.writeLong(value.getLeastSignificantBits());
    }

    static UUID readUuid(RegistryFriendlyByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    static void writeEnum(RegistryFriendlyByteBuf buffer, int wireId) {
        buffer.writeVarInt(wireId);
    }

    static <T> T readEnum(RegistryFriendlyByteBuf buffer, IntFunction<T> decoder) {
        return decoder.apply(readBoundedVarInt(buffer, 0, 3));
    }

    static void writeBoundedString(RegistryFriendlyByteBuf buffer, String value, int maximumLength) {
        buffer.writeUtf(value, maximumLength);
    }

    static String readBoundedString(RegistryFriendlyByteBuf buffer, int maximumLength) {
        return buffer.readUtf(maximumLength);
    }

    static int readBoundedVarInt(RegistryFriendlyByteBuf buffer, int minimum, int maximum) {
        int value = buffer.readVarInt();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Protocol value outside bounds");
        }
        return value;
    }
}
