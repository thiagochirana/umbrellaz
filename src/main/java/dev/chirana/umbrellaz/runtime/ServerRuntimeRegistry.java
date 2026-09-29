package dev.chirana.umbrellaz.runtime;

import net.minecraft.server.MinecraftServer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class ServerRuntimeRegistry {
    private static final Object LOCK = new Object();
    private static final Map<MinecraftServer, ServerRuntime> RUNTIMES = new IdentityHashMap<>();

    private ServerRuntimeRegistry() {
    }

    public static void put(MinecraftServer server, ServerRuntime runtime) {
        synchronized (LOCK) {
            RUNTIMES.put(server, runtime);
        }
    }

    public static Optional<ServerRuntime> find(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            return Optional.ofNullable(RUNTIMES.get(server));
        }
    }

    public static Optional<ServerRuntime> findReady(MinecraftServer server) {
        if (server == null) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            ServerRuntime runtime = RUNTIMES.get(server);
            if (runtime == null || runtime.server() != server || !runtime.ready() || runtime.stopping()) {
                return Optional.empty();
            }
            return Optional.of(runtime);
        }
    }

    public static Optional<ServerRuntime> remove(MinecraftServer server, ServerRuntime expected) {
        synchronized (LOCK) {
            if (RUNTIMES.get(server) != expected) {
                return Optional.empty();
            }
            RUNTIMES.remove(server);
            return Optional.of(expected);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return RUNTIMES.size();
        }
    }
}
