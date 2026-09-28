package dev.chirana.umbrellaz.lock;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.UUID;

final class LockWorldInstanceData extends SavedData {
    static final SavedDataType<LockWorldInstanceData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("umbrellaz", "world_instance"),
            () -> new LockWorldInstanceData(UUID.randomUUID()),
            Codec.STRING.xmap(LockWorldInstanceData::fromString,
                    value -> value.instanceId.toString()),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final UUID instanceId;

    private LockWorldInstanceData(UUID instanceId) {
        this.instanceId = instanceId;
        setDirty();
    }

    private static LockWorldInstanceData fromString(String value) {
        return new LockWorldInstanceData(UUID.fromString(value));
    }

    UUID instanceId() {
        return instanceId;
    }
}
