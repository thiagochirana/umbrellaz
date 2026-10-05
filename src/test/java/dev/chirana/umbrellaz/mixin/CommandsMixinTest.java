package dev.chirana.umbrellaz.mixin;

import dev.chirana.umbrellaz.audit.AuditActions;
import dev.chirana.umbrellaz.audit.AuditPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandsMixinTest {
    @Test
    void commandPathUsesOnlyFourNormalizedLiteralNames() {
        assertEquals("umbrellaz.whitelist.list.check", CommandAuditPath.bounded(
                List.of("Umbrellaz", "Whitelist", "List", "Check", "secret")));
    }

    @Test
    void commandPathIsBoundedWithoutInventingAnArgumentValue() {
        String longLiteral = "a".repeat(128);
        String path = CommandAuditPath.bounded(List.of(longLiteral, "password"));

        assertEquals(longLiteral, path);
        assertEquals(128, path.length());
        assertFalse(path.contains("password"));
        assertEquals("unknown", CommandAuditPath.bounded(List.of("bad value")));
    }

    @Test
    void commandPayloadHasAnAllowlistedSchemaAndNoArgumentField() {
        AuditPayload payload = AuditPayload.forAction(AuditActions.COMMAND_EXECUTED,
                AuditPayload.commandPath("umbrellaz.whitelist.add"),
                AuditPayload.commandSourceKind("player"),
                AuditPayload.commandStatus("returned"));

        assertFalse(payload.json().contains("password"));
        assertFalse(payload.json().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> AuditPayload.forAction(
                AuditActions.COMMAND_EXECUTED, AuditPayload.code("argument", "password")));
        assertThrows(IllegalArgumentException.class, () -> AuditPayload.commandPath("/op password"));
        assertThrows(IllegalArgumentException.class, () -> AuditPayload.commandPath("a".repeat(129)));
    }
}
