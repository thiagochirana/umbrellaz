package dev.chirana.umbrellaz.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record UmbrellazHelloPayload(int protocolVersion, UUID nonce, long generation, int[] featureIds)
        implements CustomPacketPayload {
    public static final Type<UmbrellazHelloPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UmbrellazHelloPayload> CODEC =
            StreamCodec.of(UmbrellazHelloPayload::write, UmbrellazHelloPayload::read);

    public UmbrellazHelloPayload {
        if (protocolVersion < 0 || nonce == null || generation < 0 || featureIds == null
                || featureIds.length > ProtocolConstants.MAX_FEATURES) {
            throw new IllegalArgumentException("Invalid Umbrellaz hello");
        }
        featureIds = featureIds.clone();
        for (int featureId : featureIds) {
            if (featureId < 0 || featureId > ProtocolConstants.MAX_FEATURE_ID) {
                throw new IllegalArgumentException("Invalid feature id");
            }
        }
    }

    @Override
    public Type<UmbrellazHelloPayload> type() {
        return TYPE;
    }

    @Override
    public int[] featureIds() {
        return featureIds.clone();
    }

    private static void write(RegistryFriendlyByteBuf buffer, UmbrellazHelloPayload payload) {
        buffer.writeVarInt(payload.protocolVersion());
        buffer.writeLong(payload.nonce().getMostSignificantBits());
        buffer.writeLong(payload.nonce().getLeastSignificantBits());
        buffer.writeVarLong(payload.generation());
        buffer.writeVarInt(payload.featureIds.length);
        for (int featureId : payload.featureIds) {
            buffer.writeVarInt(featureId);
        }
    }

    private static UmbrellazHelloPayload read(RegistryFriendlyByteBuf buffer) {
        int version = boundedVarInt(buffer, 0, ProtocolConstants.CURRENT_VERSION + 1);
        UUID nonce = new UUID(buffer.readLong(), buffer.readLong());
        long generation = buffer.readVarLong();
        if (generation < 0) {
            throw new IllegalArgumentException("Invalid protocol generation");
        }
        int count = boundedVarInt(buffer, 0, ProtocolConstants.MAX_FEATURES);
        int[] features = new int[count];
        for (int index = 0; index < count; index++) {
            features[index] = boundedVarInt(buffer, 0, ProtocolConstants.MAX_FEATURE_ID);
        }
        return new UmbrellazHelloPayload(version, nonce, generation, features);
    }

    private static int boundedVarInt(RegistryFriendlyByteBuf buffer, int minimum, int maximum) {
        int value = buffer.readVarInt();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Protocol value outside bounds");
        }
        return value;
    }
}
