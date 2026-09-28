package dev.chirana.umbrellaz.lock;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class LockAnvilMenu extends AnvilMenu {
    private final UUID playerUuid;
    private final Object contextToken;
    private final Predicate<net.minecraft.server.level.ServerPlayer> contextValid;
    private final Consumer<String> passwordSubmitted;
    private String password = "";

    LockAnvilMenu(int containerId, Inventory inventory, UUID playerUuid, Object contextToken,
                  Predicate<net.minecraft.server.level.ServerPlayer> contextValid,
                  Consumer<String> passwordSubmitted) {
        super(containerId, inventory, ContainerLevelAccess.NULL);
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.contextToken = Objects.requireNonNull(contextToken, "contextToken");
        this.contextValid = Objects.requireNonNull(contextValid, "contextValid");
        this.passwordSubmitted = Objects.requireNonNull(passwordSubmitted, "passwordSubmitted");
        getSlot(INPUT_SLOT).set(LockItem.create());
    }

    Object contextToken() {
        return contextToken;
    }

    @Override
    public boolean setItemName(String name) {
        password = name == null ? "" : name;
        return super.setItemName(name);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId == RESULT_SLOT && input == ContainerInput.PICKUP && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && serverPlayer.getUUID().equals(playerUuid) && contextValid.test(serverPlayer)) {
            passwordSubmitted.accept(password);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && serverPlayer.getUUID().equals(playerUuid)
                && contextValid.test(serverPlayer);
    }

    @Override
    public void removed(Player player) {
    }
}
