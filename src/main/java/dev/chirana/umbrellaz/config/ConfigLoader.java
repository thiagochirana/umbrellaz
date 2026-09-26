package dev.chirana.umbrellaz.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ConfigLoader {
    private static final UmbrellazConfig.RestrictedSpawn DEFAULT_SPAWN =
            new UmbrellazConfig.RestrictedSpawn("minecraft:overworld", 0, 80, 0, 0, 0);

    public UmbrellazConfig load(Path configDirectory) {
        Path path = configDirectory.resolve("config.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(configDirectory);
            if (Files.exists(path)) {
                try (InputStream input = Files.newInputStream(path)) {
                    properties.load(input);
                }
            } else {
                writeDefaults(path, properties);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load Umbrellaz configuration", exception);
        }
        return new UmbrellazConfig(new UmbrellazConfig.RestrictedSpawn(
                properties.getProperty("restricted_spawn.world", DEFAULT_SPAWN.world()),
                number(properties, "restricted_spawn.x", DEFAULT_SPAWN.x()),
                number(properties, "restricted_spawn.y", DEFAULT_SPAWN.y()),
                number(properties, "restricted_spawn.z", DEFAULT_SPAWN.z()),
                (float) number(properties, "restricted_spawn.yaw", DEFAULT_SPAWN.yaw()),
                (float) number(properties, "restricted_spawn.pitch", DEFAULT_SPAWN.pitch())
        ));
    }

    private void writeDefaults(Path path, Properties properties) throws IOException {
        properties.setProperty("restricted_spawn.world", DEFAULT_SPAWN.world());
        properties.setProperty("restricted_spawn.x", Double.toString(DEFAULT_SPAWN.x()));
        properties.setProperty("restricted_spawn.y", Double.toString(DEFAULT_SPAWN.y()));
        properties.setProperty("restricted_spawn.z", Double.toString(DEFAULT_SPAWN.z()));
        properties.setProperty("restricted_spawn.yaw", Float.toString(DEFAULT_SPAWN.yaw()));
        properties.setProperty("restricted_spawn.pitch", Float.toString(DEFAULT_SPAWN.pitch()));
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "Umbrellaz configuration");
        }
    }

    private double number(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
