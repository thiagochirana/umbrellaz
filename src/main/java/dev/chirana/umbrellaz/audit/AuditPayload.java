package dev.chirana.umbrellaz.audit;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuditPayload {
    public static final int MAX_JSON_BYTES = 8 * 1024;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_VALUE_LENGTH = 1024;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._:/,-]*");
    private static final Pattern REGISTRY_ID = Pattern.compile("[a-z0-9][a-z0-9._:/-]*");
    private static final Pattern COMMAND_PATH = Pattern.compile("[a-z0-9][a-z0-9._:-]*");
    private static final Map<String, Map<String, FieldKind>> ACTION_SCHEMAS = schemas();
    private final String action;
    private final Map<String, String> values;
    private final String json;
    private final boolean legacyOpaque;

    private AuditPayload(String action, Map<String, String> values, String json, boolean legacyOpaque) {
        this.action = action;
        this.values = Map.copyOf(values);
        this.json = json;
        this.legacyOpaque = legacyOpaque;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Payload JSON exceeds " + MAX_JSON_BYTES + " bytes");
        }
    }

    public static AuditPayload empty() {
        return trusted(null, Map.of());
    }

    public static AuditPayload forAction(String action, Field... fields) {
        AuditValidation.stableCode(action, AuditEvent.MAX_ACTION_LENGTH, "Payload action");
        Objects.requireNonNull(fields, "Payload fields must not be null");
        Map<String, FieldKind> schema = ACTION_SCHEMAS.get(action);
        if (schema == null) {
            if (fields.length == 0) {
                return trusted(action, Map.of());
            }
            throw new IllegalArgumentException("Unknown audit action has no payload schema: " + action);
        }
        if (fields.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("Payload has too many entries");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            Objects.requireNonNull(field, "Payload field must not be null");
            FieldKind expected = schema.get(field.key());
            if (expected == null || expected != field.kind()) {
                throw new IllegalArgumentException("Payload field is not allowed for action " + action + ": "
                        + field.key());
            }
            if (values.put(field.key(), field.encodedValue()) != null) {
                throw new IllegalArgumentException("Payload contains duplicate field: " + field.key());
            }
        }
        return trusted(action, values);
    }

    public static Field code(String key, String value) {
        return new Field(key, FieldKind.CODE, stableValue(value, "Payload code"));
    }

    public static Field identifier(String key, String value) {
        AuditValidation.requiredText(value, 256, "Payload identifier");
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Payload identifier is not a stable identifier: " + value);
        }
        rejectSensitiveText(value);
        return new Field(key, FieldKind.IDENTIFIER, value);
    }

    public static Field uuid(String key, UUID value) {
        return new Field(key, FieldKind.UUID, Objects.requireNonNull(value, "Payload UUID must not be null").toString());
    }

    public static Field integer(String key, int value) {
        return new Field(key, FieldKind.INTEGER, Integer.toString(value));
    }

    public static Field booleanValue(String key, boolean value) {
        return new Field(key, FieldKind.BOOLEAN, Boolean.toString(value));
    }

    public static Field displayName(String key, String value) {
        AuditValidation.requiredText(value, 64, "Payload display name");
        rejectSensitiveText(value);
        return new Field(key, FieldKind.DISPLAY_NAME, value);
    }

    public static Field itemType(String value) {
        return code("item_type", value);
    }

    public static Field hand(String value) {
        return code("hand", value);
    }

    public static Field entityUuid(UUID value) {
        return uuid("entity_uuid", value);
    }

    public static Field menuType(String value) {
        return identifier("menu_type", value);
    }

    public static Field stateId(int value) {
        return integer("state_id", value);
    }

    public static Field clickType(String value) {
        return code("click_type", value);
    }

    public static Field carriedItemType(String value) {
        return code("carried_item_type", value);
    }

    public static Field carriedCount(int value) {
        return nonNegativeInteger("carried_count", value, "Payload carried count");
    }

    public static Field slotId(int value) {
        if (value < -1) {
            throw new IllegalArgumentException("Payload slot id is invalid");
        }
        return integer("slot_id", value);
    }

    public static Field slotItemType(String value) {
        return code("slot_item_type", value);
    }

    public static Field slotItemCount(int value) {
        return nonNegativeInteger("slot_item_count", value, "Payload slot item count");
    }

    public static Field entityType(String value) {
        return code("entity_type", value);
    }

    public static Field blockType(String value) {
        return code("block_type", value);
    }

    public static Field world(String value) {
        return identifier("world", value);
    }

    public static Field position(int x, int y, int z) {
        return new Field("position", FieldKind.POSITION, x + "," + y + "," + z);
    }

    public static Field count(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Payload count must not be negative");
        }
        return integer("count", value);
    }

    public static Field mechanism(String value) {
        return code("mechanism", value);
    }

    public static Field changedCount(int value) {
        return nonNegativeInteger("changed_count", value, "Payload changed count");
    }

    public static Field playerUuid(UUID value) {
        return uuid("player_uuid", value);
    }

    public static Field playerName(String value) {
        return displayName("player_name", value);
    }

    public static Field killerUuid(UUID value) {
        return uuid("killer_uuid", value);
    }

    public static Field killerName(String value) {
        return displayName("killer_name", value);
    }

    public static Field victimUuid(UUID value) {
        return uuid("victim_uuid", value);
    }

    public static Field victimName(String value) {
        return displayName("victim_name", value);
    }

    public static Field operation(String value) {
        return code("operation", value);
    }

    public static Field result(String value) {
        return code("result", value);
    }

    public static Field cause(String value) {
        return code("cause", value);
    }

    public static Field fromState(String value) {
        return code("from_state", value);
    }

    public static Field toState(String value) {
        return code("to_state", value);
    }

    public static Field permission(String value) {
        return identifier("permission", value);
    }

    public static Field reasonCode(String value) {
        return code("reason_code", value);
    }

    public static Field sourceWorld(String value) {
        return identifier("source_world", value);
    }

    public static Field targetWorld(String value) {
        return identifier("target_world", value);
    }

    public static Field sourcePosition(int x, int y, int z) {
        return coordinateField("source_position", x, y, z);
    }

    public static Field targetPosition(int x, int y, int z) {
        return coordinateField("target_position", x, y, z);
    }

    public static AuditPayload environmentBlocksChanged(String world,
                                                         ExplosionAuditSummary.Summary summary) {
        Objects.requireNonNull(summary, "Explosion summary must not be null");
        if (summary.changedCount() <= 0 || summary.groups().isEmpty()) {
            throw new IllegalArgumentException("Explosion summary must contain changed blocks");
        }
        String oldTypes = summary.groups().stream()
                .map(ExplosionAuditSummary.Group::oldType)
                .collect(java.util.stream.Collectors.joining(","));
        String newTypes = summary.groups().stream()
                .map(ExplosionAuditSummary.Group::newType)
                .collect(java.util.stream.Collectors.joining(","));
        String groupCounts = summary.groups().stream()
                .map(group -> Integer.toString(group.count()))
                .collect(java.util.stream.Collectors.joining(","));
        return forAction(AuditActions.ENVIRONMENT_BLOCKS_CHANGED,
                mechanism("explosion"), world(world),
                position(summary.anchor().x(), summary.anchor().y(), summary.anchor().z()),
                changedCount(summary.changedCount()), registryTypes("old_types", oldTypes),
                registryTypes("new_types", newTypes), integerList("group_counts", groupCounts));
    }

    public static Field timeOfDay(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Payload time of day must not be negative");
        }
        return integer("time_of_day", value);
    }

    public static Field skyVisible(boolean value) {
        return booleanValue("sky_visible", value);
    }

    public static Field health(double value) {
        if (value < 0) {
            throw new IllegalArgumentException("Payload health must not be negative");
        }
        return decimal("health", value);
    }

    public static Field food(int value) {
        return nonNegativeInteger("food", value, "Payload food");
    }

    public static Field experience(int value) {
        return nonNegativeInteger("experience", value, "Payload experience");
    }

    public static Field victimEntityType(String value) {
        return code("victim_entity_type", value);
    }

    public static Field configuration(String value) {
        return identifier("configuration", value);
    }

    public static Field component(String value) {
        return identifier("component", value);
    }

    public static Field commandPath(String value) {
        AuditValidation.requiredText(value, 128, "Payload command path");
        if (!COMMAND_PATH.matcher(value).matches()) {
            throw new IllegalArgumentException("Payload command path is not a stable path: " + value);
        }
        return identifier("command_path", value);
    }

    public static Field commandSourceKind(String value) {
        return code("source_kind", value);
    }

    public static Field commandResult(int value) {
        return integer("result", value);
    }

    public static Field commandStatus(String value) {
        return code("status", value);
    }

    public static AuditPayload itemUsed(String itemType, String hand, String result,
                                       UUID playerUuid, String playerName) {
        return forAction(AuditActions.ITEM_USED, itemType(itemType), hand(hand), result(result),
                playerUuid(playerUuid), playerName(playerName));
    }

    public static AuditPayload itemUsedAt(String itemType, String hand, String result,
                                          UUID playerUuid, String playerName,
                                          String world, int x, int y, int z) {
        return forAction(AuditActions.ITEM_USED, itemType(itemType), hand(hand), result(result),
                playerUuid(playerUuid), playerName(playerName), world(world), position(x, y, z));
    }

    public static AuditPayload entityInteracted(String entityType, UUID entityUuid, String hand,
                                                String result, UUID playerUuid, String playerName) {
        return forAction(AuditActions.ENTITY_INTERACTED, entityType(entityType), entityUuid(entityUuid),
                hand(hand), result(result), playerUuid(playerUuid), playerName(playerName));
    }

    public static AuditPayload entityInteractedAt(String entityType, UUID entityUuid, String hand,
                                                  String result, UUID playerUuid, String playerName,
                                                  String world, int x, int y, int z) {
        return forAction(AuditActions.ENTITY_INTERACTED, entityType(entityType), entityUuid(entityUuid),
                hand(hand), result(result), playerUuid(playerUuid), playerName(playerName),
                world(world), position(x, y, z));
    }

    public static AuditPayload containerMutated(String menuType, int stateId, String clickType,
                                                String carriedItemType, int carriedCount,
                                                UUID playerUuid, String playerName) {
        return forAction(AuditActions.CONTAINER_MUTATED, menuType(menuType), stateId(stateId),
                clickType(clickType), carriedItemType(carriedItemType), carriedCount(carriedCount),
                playerUuid(playerUuid), playerName(playerName));
    }

    public static AuditPayload containerMutatedAtSlot(String menuType, int stateId, String clickType,
                                                      String carriedItemType, int carriedCount,
                                                      int slotId, String slotItemType, int slotItemCount,
                                                      UUID playerUuid, String playerName) {
        return forAction(AuditActions.CONTAINER_MUTATED, menuType(menuType), stateId(stateId),
                clickType(clickType), carriedItemType(carriedItemType), carriedCount(carriedCount),
                slotId(slotId), slotItemType(slotItemType), slotItemCount(slotItemCount),
                playerUuid(playerUuid), playerName(playerName));
    }

    public static Field enabled(boolean value) {
        return booleanValue("enabled", value);
    }

    public static Field decimal(String key, BigDecimal value) {
        Objects.requireNonNull(value, "Payload decimal must not be null");
        return new Field(key, FieldKind.DECIMAL, canonicalDecimal(value));
    }

    public static Field decimal(String key, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Payload decimal must be finite");
        }
        return decimal(key, BigDecimal.valueOf(value));
    }

    static AuditPayload fromJson(String action, String json) {
        AuditValidation.stableCode(action, AuditEvent.MAX_ACTION_LENGTH, "Payload action");
        if (json == null) {
            throw new IllegalArgumentException("Payload JSON is missing or too large");
        }
        AuditValidation.rejectUnpairedSurrogates(json, "Payload JSON");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Payload JSON is missing or too large");
        }
        Map<String, String> values = new Parser(json).parse();
        Map<String, FieldKind> schema = ACTION_SCHEMAS.get(action);
        if (schema == null && !values.isEmpty()) {
            throw new IllegalArgumentException("Unknown audit action has no payload schema: " + action);
        }
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Payload has too many entries");
        }
        if (schema != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                FieldKind kind = schema.get(entry.getKey());
                if (kind == null) {
                    throw new IllegalArgumentException("Payload field is not allowed for action " + action + ": "
                            + entry.getKey());
                }
                kind.validate(entry.getValue());
            }
        }
        return trusted(action, values);
    }

    static AuditPayload fromStoredJson(String action, String json) {
        try {
            return fromJson(action, json);
        } catch (IllegalArgumentException exception) {
            return legacyOpaque(action, json);
        }
    }

    static AuditPayload legacyOpaque(String action, String json) {
        AuditValidation.stableCode(action, AuditEvent.MAX_ACTION_LENGTH, "Payload action");
        if (json == null) {
            throw new IllegalArgumentException("Legacy payload JSON is missing");
        }
        AuditValidation.rejectUnpairedSurrogates(json, "Legacy payload JSON");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Legacy payload JSON exceeds " + MAX_JSON_BYTES + " bytes");
        }
        return new AuditPayload(action, Map.of(), json, true);
    }

    public Map<String, String> values() {
        return values;
    }

    public String json() {
        return json;
    }

    boolean isLegacyOpaque() {
        return legacyOpaque;
    }

    void validateForAction(String eventAction) {
        if (values.isEmpty()) {
            if (legacyOpaque && !Objects.equals(action, eventAction)) {
                throw new IllegalArgumentException("Legacy payload does not match audit action");
            }
            return;
        }
        if (!Objects.equals(action, eventAction)) {
            throw new IllegalArgumentException("Payload schema does not match audit action");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuditPayload payload && json.equals(payload.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return json;
    }

    private static String stableValue(String value, String field) {
        String validated = AuditValidation.stableCode(value, MAX_VALUE_LENGTH, field);
        rejectSensitiveText(validated);
        return validated;
    }

    private static AuditPayload trusted(String action, Map<String, String> values) {
        return new AuditPayload(action, values, encode(values), false);
    }

    private static void rejectSensitiveText(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (value.startsWith("/") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("passphrase") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("token")
                || normalized.contains("nonce") || normalized.contains("salt") || normalized.contains("hash")
                || normalized.contains("exception") || normalized.contains("stacktrace")
                || normalized.contains("stack_trace") || normalized.contains("chat")
                || normalized.contains("message") || normalized.contains("command")
                || normalized.equals("ip") || normalized.startsWith("ip_")
                || normalized.contains("client_ip") || normalized.contains("raw_command")
                || normalized.contains("command_line") || normalized.contains("bearer")) {
            throw new IllegalArgumentException("Sensitive payload values are not supported");
        }
    }

    private static Field nonNegativeInteger(String key, int value, String message) {
        if (value < 0) {
            throw new IllegalArgumentException(message + " must not be negative");
        }
        return integer(key, value);
    }

    private static Field coordinateField(String key, int x, int y, int z) {
        return new Field(key, FieldKind.POSITION, x + "," + y + "," + z);
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    private static String encode(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    private static Map<String, Map<String, FieldKind>> schemas() {
        Map<String, Map<String, FieldKind>> schemas = new LinkedHashMap<>();
        schemas.put(AuditActions.SYSTEM_STARTED, Map.of());
        schemas.put(AuditActions.LIFECYCLE_JOINED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME, "result", FieldKind.CODE));
        schemas.put(AuditActions.LIFECYCLE_LEFT, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.AUTH_JOINED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME, "result", FieldKind.CODE));
        schemas.put(AuditActions.AUTH_LEFT, schema("player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.AUTH_SESSION_CHANGED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "from_state", FieldKind.CODE, "to_state", FieldKind.CODE, "result", FieldKind.CODE));
        schemas.put(AuditActions.BLOCK_PLACED, schema(
                "block_type", FieldKind.CODE, "item_type", FieldKind.CODE, "world", FieldKind.IDENTIFIER,
                "position", FieldKind.POSITION, "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.BLOCK_BROKEN, schema(
                "block_type", FieldKind.CODE, "item_type", FieldKind.CODE, "world", FieldKind.IDENTIFIER,
                "position", FieldKind.POSITION, "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.ENTITY_DIED, schema(
                "entity_type", FieldKind.CODE, "cause", FieldKind.CODE, "item_type", FieldKind.CODE));
        schemas.put(AuditActions.ENTITY_KILLED, schema(
                "entity_type", FieldKind.CODE, "cause", FieldKind.CODE, "item_type", FieldKind.CODE,
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "killer_uuid", FieldKind.UUID, "killer_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.PLAYER_DIED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "cause", FieldKind.CODE, "item_type", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_KILLED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "entity_type", FieldKind.CODE, "cause", FieldKind.CODE, "item_type", FieldKind.CODE,
                "killer_uuid", FieldKind.UUID, "killer_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.WHITELIST_UPDATED, schema(
                "operation", FieldKind.CODE, "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "result", FieldKind.CODE));
        schemas.put(AuditActions.LOCK_CREATED, schema(
                "item_type", FieldKind.CODE, "count", FieldKind.INTEGER, "world", FieldKind.IDENTIFIER,
                "position", FieldKind.POSITION, "result", FieldKind.CODE));
        schemas.put(AuditActions.LOCK_REMOVED, schema(
                "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION, "result", FieldKind.CODE));
        schemas.put(AuditActions.LOCK_PASSWORD_VERIFY, schema(
                "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION, "result", FieldKind.CODE,
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.LOCK_BREAK, schema(
                "block_type", FieldKind.CODE, "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION,
                "result", FieldKind.CODE, "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.TELEPORT_EXECUTED, schema(
                "source_world", FieldKind.IDENTIFIER, "source_position", FieldKind.POSITION,
                "target_world", FieldKind.IDENTIFIER, "target_position", FieldKind.POSITION,
                "result", FieldKind.CODE));
        schemas.put(AuditActions.WORLD_TIME_SET, schema(
                "world", FieldKind.IDENTIFIER, "time_of_day", FieldKind.INTEGER, "result", FieldKind.CODE));
        schemas.put(AuditActions.WORLD_SKY_SET, schema(
                "world", FieldKind.IDENTIFIER, "sky_visible", FieldKind.BOOLEAN, "result", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_HEALTH_SET, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "health", FieldKind.DECIMAL, "result", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_FOOD_SET, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "food", FieldKind.INTEGER, "result", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_EXPERIENCE_SET, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "experience", FieldKind.INTEGER, "result", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_KILL, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "victim_entity_type", FieldKind.CODE, "victim_uuid", FieldKind.UUID,
                "victim_name", FieldKind.DISPLAY_NAME, "item_type", FieldKind.CODE));
        schemas.put(AuditActions.BLOCKS_CONFIGURATION_SET, schema(
                "configuration", FieldKind.IDENTIFIER, "enabled", FieldKind.BOOLEAN, "result", FieldKind.CODE));
        schemas.put(AuditActions.RUNTIME_RELOAD, schema(
                "component", FieldKind.IDENTIFIER, "result", FieldKind.CODE));
        schemas.put(AuditActions.AUTHORIZATION_COMMAND_DENIED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "permission", FieldKind.IDENTIFIER, "reason_code", FieldKind.CODE));
        schemas.put(AuditActions.COMMAND_EXECUTED, schema(
                "command_path", FieldKind.IDENTIFIER, "source_kind", FieldKind.CODE,
                "result", FieldKind.INTEGER, "status", FieldKind.CODE));
        schemas.put(AuditActions.ITEM_USED, schema(
                "item_type", FieldKind.CODE, "hand", FieldKind.CODE, "result", FieldKind.CODE,
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION));
        schemas.put(AuditActions.ENTITY_INTERACTED, schema(
                "entity_type", FieldKind.CODE, "entity_uuid", FieldKind.UUID, "hand", FieldKind.CODE,
                "result", FieldKind.CODE, "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION));
        schemas.put(AuditActions.CONTAINER_MUTATED, schema(
                "menu_type", FieldKind.IDENTIFIER, "state_id", FieldKind.INTEGER, "click_type", FieldKind.CODE,
                "carried_item_type", FieldKind.CODE, "carried_count", FieldKind.INTEGER,
                "slot_id", FieldKind.INTEGER, "slot_item_type", FieldKind.CODE, "slot_item_count", FieldKind.INTEGER,
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME));
        schemas.put(AuditActions.DAMAGE_SUMMARY, schema(
                "count", FieldKind.INTEGER, "base_damage", FieldKind.DECIMAL, "reported_damage", FieldKind.DECIMAL,
                "window_duration_ms", FieldKind.INTEGER, "cause", FieldKind.CODE, "item_type", FieldKind.CODE,
                "world", FieldKind.IDENTIFIER, "attacker_uuid", FieldKind.UUID, "victim_uuid", FieldKind.UUID,
                "victim_entity_type", FieldKind.CODE));
        schemas.put(AuditActions.PLAYER_WORLD_CHANGED, schema(
                "player_uuid", FieldKind.UUID, "player_name", FieldKind.DISPLAY_NAME,
                "source_world", FieldKind.IDENTIFIER, "target_world", FieldKind.IDENTIFIER,
                "target_position", FieldKind.POSITION));
        schemas.put(AuditActions.ENVIRONMENT_BLOCKS_CHANGED, schema(
                "mechanism", FieldKind.CODE, "world", FieldKind.IDENTIFIER, "position", FieldKind.POSITION,
                "changed_count", FieldKind.INTEGER, "old_types", FieldKind.REGISTRY_LIST,
                "new_types", FieldKind.REGISTRY_LIST, "group_counts", FieldKind.INTEGER_LIST));
        return Map.copyOf(schemas);
    }

    private static Map<String, FieldKind> schema(Object... values) {
        Map<String, FieldKind> schema = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            schema.put((String) values[index], (FieldKind) values[index + 1]);
        }
        return Map.copyOf(schema);
    }

    public static final class Field {
        private final String key;
        private final FieldKind kind;
        private final String encodedValue;

        private Field(String key, FieldKind kind, String encodedValue) {
            AuditValidation.stableCode(key, 64, "Payload field key");
            this.key = key;
            this.kind = kind;
            this.encodedValue = encodedValue;
        }

        private String key() {
            return key;
        }

        private FieldKind kind() {
            return kind;
        }

        private String encodedValue() {
            return encodedValue;
        }
    }

    private enum FieldKind {
        CODE {
            @Override
            void validate(String value) {
                stableValue(value, "Payload code");
            }
        },
        UUID {
            @Override
            void validate(String value) {
                try {
                    if (!java.util.UUID.fromString(value).toString().equals(value)) {
                        throw new IllegalArgumentException("Payload UUID is not canonical");
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Payload UUID is invalid", exception);
                }
            }
        },
        INTEGER {
            @Override
            void validate(String value) {
                try {
                    if (!Integer.toString(Integer.parseInt(value)).equals(value)) {
                        throw new IllegalArgumentException("Payload integer is not canonical");
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Payload integer is invalid", exception);
                }
            }
        },
        DECIMAL {
            @Override
            void validate(String value) {
                try {
                    if (!canonicalDecimal(new BigDecimal(value)).equals(value)) {
                        throw new IllegalArgumentException("Payload decimal is not canonical");
                    }
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Payload decimal is invalid", exception);
                }
            }
        },
        BOOLEAN {
            @Override
            void validate(String value) {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("Payload boolean is invalid");
                }
            }
        },
        DISPLAY_NAME {
            @Override
            void validate(String value) {
                AuditValidation.requiredText(value, 64, "Payload display name");
                rejectSensitiveText(value);
            }
        },
        IDENTIFIER {
            @Override
            void validate(String value) {
                AuditValidation.requiredText(value, 256, "Payload identifier");
                if (!AuditPayload.IDENTIFIER.matcher(value).matches()) {
                    throw new IllegalArgumentException("Payload identifier is invalid");
                }
                rejectSensitiveText(value);
            }
        },
        POSITION {
            @Override
            void validate(String value) {
                List<String> coordinates = List.of(value.split(",", -1));
                if (coordinates.size() != 3) {
                    throw new IllegalArgumentException("Payload position is invalid");
                }
                for (String coordinate : coordinates) {
                    try {
                        if (!Integer.toString(Integer.parseInt(coordinate)).equals(coordinate)) {
                            throw new IllegalArgumentException("Payload position is not canonical");
                        }
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Payload position is invalid", exception);
                    }
                }
            }
        },
        REGISTRY_LIST {
            @Override
            void validate(String value) {
                validateList(value, REGISTRY_ID, "Payload registry type list");
            }
        },
        INTEGER_LIST {
            @Override
            void validate(String value) {
                String[] values = value.split(",", -1);
                if (values.length == 0 || values.length > ExplosionAuditSummary.MAX_GROUPS) {
                    throw new IllegalArgumentException("Payload integer list is invalid");
                }
                for (String item : values) {
                    try {
                        if (Integer.parseInt(item) <= 0 || !Integer.toString(Integer.parseInt(item)).equals(item)) {
                            throw new IllegalArgumentException("Payload integer list is not canonical");
                        }
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("Payload integer list is invalid", exception);
                    }
                }
            }
        };

        abstract void validate(String value);

        private static void validateList(String value, Pattern pattern, String field) {
            String[] values = value.split(",", -1);
            if (values.length == 0 || values.length > ExplosionAuditSummary.MAX_GROUPS) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            for (String item : values) {
                AuditValidation.requiredText(item, 256, field);
                if (!pattern.matcher(item).matches()) {
                    throw new IllegalArgumentException(field + " contains an invalid registry id");
                }
            }
        }
    }

    private static Field registryTypes(String key, String value) {
        return new Field(key, FieldKind.REGISTRY_LIST, value);
    }

    private static Field integerList(String key, String value) {
        return new Field(key, FieldKind.INTEGER_LIST, value);
    }

    private static final class Parser {
        private final String input;
        private int position;

        private Parser(String input) {
            this.input = input;
        }

        private Map<String, String> parse() {
            skipWhitespace();
            expect('{');
            Map<String, String> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                position++;
                ensureEnd();
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = string();
                if (values.put(key, value) != null) {
                    throw error("Duplicate payload key");
                }
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    ensureEnd();
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                char character = input.charAt(position++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (position >= input.length()) {
                        throw error("Incomplete JSON escape");
                    }
                    char escaped = input.charAt(position++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(unicodeEscape());
                        default -> throw error("Invalid JSON escape");
                    }
                } else {
                    if (Character.isISOControl(character)) {
                        throw error("Control character in JSON string");
                    }
                    value.append(character);
                }
            }
            throw error("Unclosed JSON string");
        }

        private char unicodeEscape() {
            if (position + 4 > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = input.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException exception) {
                throw error("Invalid unicode escape");
            }
        }

        private void expect(char expected) {
            if (position >= input.length() || input.charAt(position) != expected) {
                throw error("Expected '" + expected + "'");
            }
            position++;
        }

        private boolean peek(char expected) {
            return position < input.length() && input.charAt(position) == expected;
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private void ensureEnd() {
            skipWhitespace();
            if (position != input.length()) {
                throw error("Trailing JSON content");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at payload character " + position);
        }
    }
}
