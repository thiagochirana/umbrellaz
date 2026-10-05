package dev.chirana.umbrellaz.audit;

public final class AuditActions {
    public static final String SYSTEM_STARTED = "system.started";

    public static final String LIFECYCLE_JOINED = "lifecycle.joined";
    public static final String LIFECYCLE_LEFT = "lifecycle.left";
    public static final String AUTH_JOINED = "auth.joined";
    public static final String AUTH_LEFT = "auth.left";
    public static final String AUTH_SESSION_CHANGED = "auth.session.changed";

    public static final String BLOCK_PLACED = "block.placed";
    public static final String BLOCK_BROKEN = "block.broken";
    public static final String ENTITY_DIED = "entity.died";
    public static final String ENTITY_KILLED = "entity.killed";
    public static final String PLAYER_DIED = "player.died";
    public static final String PLAYER_KILLED = "player.killed";

    public static final String WHITELIST_UPDATED = "whitelist.updated";

    public static final String LOCK_CREATED = "lock.created";
    public static final String LOCK_REMOVED = "lock.removed";
    public static final String LOCK_PASSWORD_VERIFY = "lock.password.verify";
    public static final String LOCK_BREAK = "lock.break";

    public static final String TELEPORT_EXECUTED = "teleport.executed";
    public static final String WORLD_TIME_SET = "world.time.set";
    public static final String WORLD_SKY_SET = "world.sky.set";

    public static final String PLAYER_HEALTH_SET = "player.health.set";
    public static final String PLAYER_FOOD_SET = "player.food.set";
    public static final String PLAYER_EXPERIENCE_SET = "player.experience.set";
    public static final String PLAYER_KILL = "player.kill";

    public static final String BLOCKS_CONFIGURATION_SET = "blocks.configuration.set";
    public static final String RUNTIME_RELOAD = "runtime.reload";
    public static final String AUTHORIZATION_COMMAND_DENIED = "authorization.command.denied";
    public static final String COMMAND_EXECUTED = "command.executed";

    public static final String ITEM_USED = "item.used";
    public static final String ENTITY_INTERACTED = "entity.interacted";
    public static final String CONTAINER_MUTATED = "container.mutated";
    public static final String DAMAGE_SUMMARY = "damage.summary";
    public static final String PLAYER_WORLD_CHANGED = "player.world.changed";
    public static final String ENVIRONMENT_BLOCKS_CHANGED = "environment.blocks.changed";

    private AuditActions() {
    }
}
