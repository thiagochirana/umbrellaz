package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OnlinePlayerSuggestions implements com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> {
    private final AliasCache aliasCache;

    public OnlinePlayerSuggestions(AliasCache aliasCache) {
        this.aliasCache = aliasCache;
    }

    public OnlinePlayerSuggestions() {
        this(null);
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Collection<ServerPlayer> onlinePlayers = context.getSource().getServer().getPlayerList().getPlayers();
        List<OnlinePlayer> names = new ArrayList<>(onlinePlayers.size());
        for (ServerPlayer player : onlinePlayers) {
            names.add(new OnlinePlayer(player.getUUID(), player.getName().getString()));
        }
        return suggestNames(collectNames(names, aliasCache), builder);
    }

    public static List<String> collectNames(Iterable<OnlinePlayer> onlinePlayers, AliasCache aliasCache) {
        MapAccumulator names = new MapAccumulator();
        for (OnlinePlayer player : onlinePlayers) {
            names.add(player.username());
            if (aliasCache != null) {
                aliasCache.aliasFor(player.uuid()).ifPresent(names::add);
            }
        }
        return names.values();
    }

    public static CompletableFuture<Suggestions> suggestNames(Iterable<String> names, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(names, builder);
    }

    public record OnlinePlayer(UUID uuid, String username) {
    }

    private static final class MapAccumulator {
        private final LinkedHashMap<String, String> names = new LinkedHashMap<>();

        private void add(String name) {
            if (name != null && !name.isBlank()) {
                names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }

        private List<String> values() {
            return List.copyOf(names.values());
        }
    }
}
