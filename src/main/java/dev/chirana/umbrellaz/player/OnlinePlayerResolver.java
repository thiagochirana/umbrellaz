package dev.chirana.umbrellaz.player;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OnlinePlayerResolver {
    private final AliasCache aliasCache;

    public OnlinePlayerResolver() {
        this(null);
    }

    public OnlinePlayerResolver(AliasCache aliasCache) {
        this.aliasCache = aliasCache;
    }

    public OnlinePlayerResolution resolve(MinecraftServer server, String identifier) {
        var aliasUuid = aliasCache == null ? java.util.Optional.<UUID>empty() : aliasCache.find(identifier);
        if (aliasCache != null && !aliasCache.isReady()) {
            return OnlinePlayerResolution.notReady();
        }
        Set<UUID> usernameMatches = new HashSet<>();
        ServerPlayer usernamePlayer = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getName().getString().equalsIgnoreCase(identifier)) {
                usernameMatches.add(player.getUUID());
                usernamePlayer = player;
            }
        }
        if (usernameMatches.size() > 1) {
            return OnlinePlayerResolution.ambiguous();
        }
        if (aliasUuid.isPresent()) {
            ServerPlayer aliasPlayer = server.getPlayerList().getPlayer(aliasUuid.get());
            if (usernameMatches.size() == 1 && !aliasUuid.get().equals(usernameMatches.iterator().next())) {
                return OnlinePlayerResolution.ambiguous();
            }
            return aliasPlayer == null ? OnlinePlayerResolution.notFound() : OnlinePlayerResolution.found(aliasPlayer);
        }
        return usernamePlayer == null ? OnlinePlayerResolution.notFound() : OnlinePlayerResolution.found(usernamePlayer);
    }
}
