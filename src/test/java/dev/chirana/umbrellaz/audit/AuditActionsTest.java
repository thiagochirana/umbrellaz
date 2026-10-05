package dev.chirana.umbrellaz.audit;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditActionsTest {
    private static final UUID PLAYER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KILLER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void plannedActionsAreUniqueStableDottedCodes() {
        List<String> actions = List.of(
                AuditActions.SYSTEM_STARTED,
                AuditActions.LIFECYCLE_JOINED, AuditActions.LIFECYCLE_LEFT,
                AuditActions.AUTH_JOINED, AuditActions.AUTH_LEFT, AuditActions.AUTH_SESSION_CHANGED,
                AuditActions.BLOCK_PLACED, AuditActions.BLOCK_BROKEN,
                AuditActions.ENTITY_DIED, AuditActions.ENTITY_KILLED,
                AuditActions.PLAYER_DIED, AuditActions.PLAYER_KILLED,
                AuditActions.WHITELIST_UPDATED,
                AuditActions.LOCK_CREATED, AuditActions.LOCK_REMOVED,
                AuditActions.LOCK_PASSWORD_VERIFY, AuditActions.LOCK_BREAK,
                AuditActions.TELEPORT_EXECUTED, AuditActions.WORLD_TIME_SET, AuditActions.WORLD_SKY_SET,
                AuditActions.PLAYER_HEALTH_SET, AuditActions.PLAYER_FOOD_SET,
                AuditActions.PLAYER_EXPERIENCE_SET, AuditActions.PLAYER_KILL,
                AuditActions.BLOCKS_CONFIGURATION_SET, AuditActions.RUNTIME_RELOAD,
                AuditActions.AUTHORIZATION_COMMAND_DENIED, AuditActions.ITEM_USED,
                AuditActions.ENTITY_INTERACTED, AuditActions.CONTAINER_MUTATED,
                AuditActions.ENVIRONMENT_BLOCKS_CHANGED);

        assertEquals(actions.size(), Set.copyOf(actions).size());
        actions.forEach(action -> assertTrue(action.matches("[a-z0-9]+(?:\\.[a-z0-9]+)+"), action));
    }

    @Test
    void everyPlannedActionAcceptsOnlyItsTypedFields() {
        assertEquals("{}", AuditPayload.forAction(AuditActions.SYSTEM_STARTED).json());
        assertPayload(AuditActions.LIFECYCLE_JOINED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.result("joined"));
        assertPayload(AuditActions.LIFECYCLE_LEFT, AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.AUTH_JOINED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.result("authenticated"));
        assertPayload(AuditActions.AUTH_LEFT, AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.AUTH_SESSION_CHANGED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.fromState("blocked"),
                AuditPayload.toState("authenticated"), AuditPayload.result("success"));

        assertPayload(AuditActions.BLOCK_PLACED, AuditPayload.blockType("minecraft:stone"),
                AuditPayload.itemType("minecraft:stone"), AuditPayload.world("minecraft:overworld"),
                AuditPayload.position(1, 64, -2), AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.BLOCK_BROKEN, AuditPayload.blockType("minecraft:stone"),
                AuditPayload.itemType("minecraft:stone"), AuditPayload.world("minecraft:overworld"),
                AuditPayload.position(1, 64, -2), AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.ENTITY_DIED, AuditPayload.entityType("minecraft:zombie"),
                AuditPayload.cause("fire"), AuditPayload.itemType("minecraft:flint_and_steel"));
        assertPayload(AuditActions.ENTITY_KILLED, AuditPayload.entityType("minecraft:zombie"),
                AuditPayload.cause("melee"), AuditPayload.itemType("minecraft:diamond_sword"),
                AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"),
                AuditPayload.killerUuid(KILLER_UUID), AuditPayload.killerName("Bob"));
        assertPayload(AuditActions.PLAYER_DIED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.cause("fall"), AuditPayload.itemType("minecraft:air"));
        assertPayload(AuditActions.PLAYER_KILLED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.entityType("minecraft:player"),
                AuditPayload.cause("melee"), AuditPayload.itemType("minecraft:iron_sword"),
                AuditPayload.killerUuid(KILLER_UUID), AuditPayload.killerName("Bob"));

        assertPayload(AuditActions.WHITELIST_UPDATED, AuditPayload.operation("added"),
                AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"), AuditPayload.result("success"));
        assertPayload(AuditActions.LOCK_CREATED, AuditPayload.itemType("minecraft:chest"), AuditPayload.count(1),
                AuditPayload.world("minecraft:overworld"), AuditPayload.position(1, 64, -2),
                AuditPayload.result("success"));
        assertPayload(AuditActions.LOCK_REMOVED, AuditPayload.world("minecraft:overworld"),
                AuditPayload.position(1, 64, -2), AuditPayload.result("success"));
        assertPayload(AuditActions.LOCK_PASSWORD_VERIFY, AuditPayload.world("minecraft:overworld"),
                AuditPayload.position(1, 64, -2), AuditPayload.result("denied"),
                AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.LOCK_BREAK, AuditPayload.blockType("minecraft:chest"),
                AuditPayload.world("minecraft:overworld"), AuditPayload.position(1, 64, -2),
                AuditPayload.result("success"), AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));

        assertPayload(AuditActions.TELEPORT_EXECUTED, AuditPayload.sourceWorld("minecraft:overworld"),
                AuditPayload.sourcePosition(1, 64, -2), AuditPayload.targetWorld("minecraft:the_nether"),
                AuditPayload.targetPosition(3, 70, 4), AuditPayload.result("success"));
        assertPayload(AuditActions.WORLD_TIME_SET, AuditPayload.world("minecraft:overworld"),
                AuditPayload.timeOfDay(12000), AuditPayload.result("success"));
        assertPayload(AuditActions.WORLD_SKY_SET, AuditPayload.world("minecraft:overworld"),
                AuditPayload.skyVisible(true), AuditPayload.result("success"));
        assertPayload(AuditActions.PLAYER_HEALTH_SET, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.health(18.5), AuditPayload.result("success"));
        assertPayload(AuditActions.PLAYER_FOOD_SET, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.food(18), AuditPayload.result("success"));
        assertPayload(AuditActions.PLAYER_EXPERIENCE_SET, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.experience(42), AuditPayload.result("success"));
        assertPayload(AuditActions.PLAYER_KILL, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.victimEntityType("minecraft:zombie"),
                AuditPayload.victimUuid(KILLER_UUID), AuditPayload.victimName("Bob"),
                AuditPayload.itemType("minecraft:diamond_sword"));
        assertPayload(AuditActions.BLOCKS_CONFIGURATION_SET, AuditPayload.configuration("protected"),
                AuditPayload.enabled(true), AuditPayload.result("success"));
        assertPayload(AuditActions.RUNTIME_RELOAD, AuditPayload.component("configuration"),
                AuditPayload.result("success"));
        assertPayload(AuditActions.AUTHORIZATION_COMMAND_DENIED, AuditPayload.playerUuid(PLAYER_UUID),
                AuditPayload.playerName("Ada"), AuditPayload.permission("umbrellaz.whitelist"),
                AuditPayload.reasonCode("not_administrator"));
        assertPayload(AuditActions.ITEM_USED, AuditPayload.itemType("minecraft:stick"),
                AuditPayload.hand("main_hand"), AuditPayload.result("success"),
                AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.ENTITY_INTERACTED, AuditPayload.entityType("minecraft:horse"),
                AuditPayload.entityUuid(KILLER_UUID), AuditPayload.hand("off_hand"),
                AuditPayload.result("consume"), AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
        assertPayload(AuditActions.CONTAINER_MUTATED, AuditPayload.menuType("minecraft.chestmenu"),
                AuditPayload.stateId(4), AuditPayload.clickType("pickup"),
                AuditPayload.carriedItemType("minecraft:air"), AuditPayload.carriedCount(0),
                AuditPayload.playerUuid(PLAYER_UUID), AuditPayload.playerName("Ada"));
    }

    @Test
    void typedFactoriesUseCanonicalRepresentationsAndRejectSensitiveData() {
        AuditPayload health = AuditPayload.forAction(AuditActions.PLAYER_HEALTH_SET, AuditPayload.health(20.50));
        assertEquals("20.5", health.values().get("health"));
        AuditPayload decimal = AuditPayload.forAction(AuditActions.PLAYER_HEALTH_SET,
                AuditPayload.decimal("health", new BigDecimal("1E+3")));
        assertEquals("1000", decimal.values().get("health"));

        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.fromJson(AuditActions.PLAYER_HEALTH_SET, "{\"health\":\"1.0\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.PLAYER_HEALTH_SET, AuditPayload.health(Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.PLAYER_FOOD_SET, AuditPayload.food(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.PLAYER_HEALTH_SET, AuditPayload.health(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.WORLD_TIME_SET, AuditPayload.timeOfDay(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.AUTHORIZATION_COMMAND_DENIED,
                        AuditPayload.permission("/op Ada")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.LOCK_PASSWORD_VERIFY,
                        AuditPayload.result("password")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.PLAYER_DIED, AuditPayload.cause("chat message")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.PLAYER_KILL, AuditPayload.itemType("sha256-hash")));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction(AuditActions.RUNTIME_RELOAD, AuditPayload.code("message", "no")));
        AuditPayload item = AuditPayload.itemUsedAt("minecraft:stick", "main_hand", "success",
                PLAYER_UUID, "Ada", "minecraft:overworld", 1, 64, -2);
        assertFalse(item.json().contains("components"));
        assertFalse(item.json().contains("nbt"));
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.itemUsed("minecraft:secret_token", "main_hand", "success",
                        PLAYER_UUID, "Ada"));
    }

    @Test
    void unknownActionsRemainEmptyOnly() {
        assertEquals("{}", AuditPayload.forAction("future.action").json());
        assertFalse(AuditPayload.forAction(AuditActions.SYSTEM_STARTED).isLegacyOpaque());
        assertThrows(IllegalArgumentException.class,
                () -> AuditPayload.forAction("future.action", AuditPayload.code("result", "success")));
    }

    private void assertPayload(String action, AuditPayload.Field... fields) {
        AuditPayload payload = AuditPayload.forAction(action, fields);
        assertFalse(payload.values().isEmpty(), action);
    }
}
