package org.csajava.context;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import org.csajava.config.ConfigLoader;

public record RuntimeContext(
        String stdinRaw,
        JsonObject stdinJson,
        Map<String, Object> config,
        Map<String, Object> hints,
        String configPath) {
    private static final Gson GSON = new Gson();

    public RuntimeContext(
            String stdinRaw,
            JsonObject stdinJson,
            Map<String, Object> config,
            Map<String, Object> hints) {
        this(stdinRaw, stdinJson, config, hints, null);
    }

    public static RuntimeContext load(String configPath, String stdinRaw) {
        JsonObject stdinJson = parseStdin(stdinRaw);
        return new RuntimeContext(stdinRaw, stdinJson, Collections.emptyMap(), Collections.emptyMap(), configPath);
    }

    public Map<String, Object> loadConfig() {
        return configPath == null ? config : ConfigLoader.load(configPath);
    }

    public RuntimeContext withHints(Map<String, Object> extraHints) {
        return new RuntimeContext(
                stdinRaw,
                stdinJson,
                config,
                extraHints == null ? Collections.emptyMap() : extraHints,
                configPath);
    }

    public RuntimeContext withConfig(Map<String, Object> updatedConfig) {
        return new RuntimeContext(
                stdinRaw,
                stdinJson,
                updatedConfig == null ? Collections.emptyMap() : updatedConfig,
                hints,
                null);
    }

    private static JsonObject parseStdin(String stdinRaw) {
        if (stdinRaw == null || stdinRaw.isBlank()) {
            return new JsonObject();
        }
        return GSON.fromJson(stdinRaw, JsonObject.class);
    }
}
