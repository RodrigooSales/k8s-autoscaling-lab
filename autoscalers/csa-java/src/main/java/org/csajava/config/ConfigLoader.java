package org.csajava.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static Map<String, Object> load(String preferredPath) {
        Map<String, Object> config = tryLoad(preferredPath);
        if (!config.isEmpty()) {
            return config;
        }

        if (!"config.yaml".equals(preferredPath)) {
            config = tryLoad("config.yaml");
            if (!config.isEmpty()) {
                return config;
            }
        }

        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryLoad(String path) {
        Path file = Path.of(path);
        if (!Files.exists(file)) {
            return Collections.emptyMap();
        }

        try (InputStream stream = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(stream);
            if (loaded instanceof Map<?, ?> raw) {
                return (Map<String, Object>) raw;
            }
            return Collections.emptyMap();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read config file: " + path, e);
        }
    }
}
