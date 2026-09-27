package dev.chirana.umbrellaz.player;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnlinePlayerSuggestionsTest {
    @Test
    void includesOnlineUsernamesAndTheirAliasesOnly() {
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        AliasCache aliases = new AliasCache();
        aliases.replace(Map.of(online, "Ada_One", offline, "Offline"));

        List<String> names = OnlinePlayerSuggestions.collectNames(
                List.of(new OnlinePlayerSuggestions.OnlinePlayer(online, "Ada")), aliases);

        assertEquals(List.of("Ada", "ada_one"), names);
    }

    @Test
    void deduplicatesNamesCaseInsensitively() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AliasCache aliases = new AliasCache();
        aliases.replace(Map.of(first, "Bia", second, "ADA"));

        List<String> names = OnlinePlayerSuggestions.collectNames(List.of(
                new OnlinePlayerSuggestions.OnlinePlayer(first, "Ada"),
                new OnlinePlayerSuggestions.OnlinePlayer(second, "bia")), aliases);

        assertEquals(List.of("Ada", "bia"), names);
    }

    @Test
    void brigadierFiltersPartialNames() {
        SuggestionsBuilder builder = new SuggestionsBuilder("/uz tp al", 7);

        List<String> suggestions = OnlinePlayerSuggestions.suggestNames(
                List.of("Ada", "Alfred", "Bia"), builder).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .toList();

        assertEquals(List.of("Alfred"), suggestions);
    }
}
