package dev.chirana.umbrellaz.authorization;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.rcon.RconConsoleSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAuthorizationTest {
    @Test
    void onlyUnsuppressedConsoleAndRconTypesAreTrusted() {
        assertTrue(CommandAuthorization.isTrustedConsoleType(MinecraftServer.class, false));
        assertTrue(CommandAuthorization.isTrustedConsoleType(RconConsoleSource.class, false));
        assertFalse(CommandAuthorization.isTrustedConsoleType(MinecraftServer.class, true));
        assertFalse(CommandAuthorization.isTrustedConsoleType(RconConsoleSource.class, true));
        assertFalse(CommandAuthorization.isTrustedConsoleType(String.class, false));
    }
}
