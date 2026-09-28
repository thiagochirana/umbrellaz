package dev.chirana.umbrellaz.lock;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;

import java.util.List;

public final class LockItem {
    private static final String MARKER = "umbrellaz:lock_item";
    private static final String VERSION = "umbrellaz:lock_item_version";
    private static final int CURRENT_VERSION = 1;

    private LockItem() {
    }

    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARKER, true);
        tag.putInt(VERSION, CURRENT_VERSION);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        stack.set(DataComponents.CUSTOM_MODEL_DATA, modelData());
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Cadeado"));
        return stack;
    }

    static CustomModelData modelData() {
        return new CustomModelData(List.of(1.0F), List.of(), List.of(), List.of());
    }

    public static boolean isMarked(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() != Items.TRIPWIRE_HOOK) {
            return false;
        }
        return isCurrentMarker(stack.get(DataComponents.CUSTOM_DATA));
    }

    static boolean isCurrentMarker(CustomData data) {
        if (data == null) {
            return false;
        }
        CompoundTag tag = data.copyTag();
        return tag.getBooleanOr(MARKER, false) && tag.getIntOr(VERSION, -1) == CURRENT_VERSION;
    }
}
