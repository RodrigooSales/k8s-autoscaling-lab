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
        Map<String, Object> hints) {
    private static final Gson GSON = new Gson();

    public static RuntimeContext load(String configPath, String stdinRaw) {
        JsonObject stdinJson = parseStdin(stdinRaw);
        Map<String, Object> config = ConfigLoader.load(configPath);
        return new RuntimeContext(stdinRaw, stdinJson, config, Collections.emptyMap());
    }

    public RuntimeContext withHints(Map<String, Object> extraHints) {
        return new RuntimeContext(
                stdinRaw,
                stdinJson,
                config,
                extraHints == null ? Collections.emptyMap() : extraHints);
    }

    public RuntimeContext withConfig(Map<String, Object> updatedConfig) {
        return new RuntimeContext(
                stdinRaw,
                stdinJson,
                updatedConfig == null ? Collections.emptyMap() : updatedConfig,
                hints);
    }

    private static JsonObject parseStdin(String stdinRaw) {
        if (stdinRaw == null || stdinRaw.isBlank()) {
            return new JsonObject();
        }
        return GSON.fromJson(stdinRaw, JsonObject.class);
    }
}
