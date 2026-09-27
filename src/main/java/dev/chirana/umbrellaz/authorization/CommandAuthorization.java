package dev.chirana.umbrellaz.authorization;

import dev.chirana.umbrellaz.mixin.CommandSourceStackAccessor;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;

public final class CommandAuthorization {
    private CommandAuthorization() {
    }

    public static boolean isAdministrator(CommandSourceStack source, AuthorizationService authorizationService) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return authorizationService.isAdministrator(player.getUUID());
        }
        if (source.getEntity() != null) {
            return false;
        }
        CommandSource underlying = ((CommandSourceStackAccessor) (Object) source).umbrellaz$getSource();
        return isTrustedConsoleSource(underlying, source.isSilent());
    }

    static boolean isTrustedConsoleSource(CommandSource underlying, boolean silent) {
        return underlying != null && isTrustedConsoleType(underlying.getClass(), silent);
    }

    static boolean isTrustedConsoleType(Class<?> underlyingType, boolean silent) {
        return !silent && underlyingType != null && (MinecraftServer.class.isAssignableFrom(underlyingType)
                || RconConsoleSource.class.isAssignableFrom(underlyingType));
    }
}
