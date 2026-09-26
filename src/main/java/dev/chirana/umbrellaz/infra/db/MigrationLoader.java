package dev.chirana.umbrellaz.infra.db;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MigrationLoader {
    private static final String RESOURCE_PATH = "db/migrations";
    private static final Pattern FILE_NAME = Pattern.compile("^(\\d{14})_(.+)\\.sql$");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public List<Migration> load() {
        Set<String> resourceNames = new TreeSet<>();
        try {
            Enumeration<URL> resources = MigrationLoader.class.getClassLoader().getResources(RESOURCE_PATH);
            while (resources.hasMoreElements()) {
                collect(resources.nextElement(), resourceNames);
            }
            if (resourceNames.isEmpty()) {
                throw new IllegalStateException("No SQL migrations found at classpath:" + RESOURCE_PATH);
            }
            return resourceNames.stream().map(this::read).sorted(java.util.Comparator.comparing(Migration::migrationId)).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to discover SQL migrations", exception);
        }
    }

    private void collect(URL resource, Set<String> resourceNames) throws IOException {
        if (resource.getProtocol().equals("file")) {
            collectDirectory(Path.of(toUri(resource)), resourceNames);
            return;
        }
        if (resource.getProtocol().equals("jar")) {
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            connection.setUseCaches(false);
            try (JarFile jarFile = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jarFile.entries();
                String prefix = RESOURCE_PATH + "/";
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                        resourceNames.add(entry.getName());
                    }
                }
            }
        }
    }

    private void collectDirectory(Path directory, Set<String> resourceNames) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> RESOURCE_PATH + "/" + path.getFileName())
                    .forEach(resourceNames::add);
        }
    }

    private Migration read(String resourceName) {
        String fileName = resourceName.substring(RESOURCE_PATH.length() + 1);
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalStateException("Invalid migration filename: " + fileName
                    + ". Expected timestamp_name.sql");
        }
        Instant createdAt = LocalDateTime.parse(matcher.group(1), TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC);
        try (InputStream input = MigrationLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource disappeared: " + resourceName);
            }
            return new Migration(fileName.substring(0, fileName.length() - 4), createdAt,
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration: " + fileName, exception);
        }
    }

    private URI toUri(URL resource) {
        try {
            return resource.toURI();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid migration resource URL: " + resource, exception);
        }
    }
}
