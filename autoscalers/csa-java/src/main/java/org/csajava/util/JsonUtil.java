package org.csajava.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static JsonObject object(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = source.get(key);
        return value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = source.get(key);
        return value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    public static JsonObject objectPath(JsonObject source, String... keys) {
        JsonElement element = elementPath(source, keys);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static JsonArray arrayPath(JsonObject source, String... keys) {
        JsonElement element = elementPath(source, keys);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    public static JsonElement elementPath(JsonObject source, String... keys) {
        if (source == null || keys == null || keys.length == 0) {
            return null;
        }

        JsonElement current = source;
        for (String key : keys) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject currentObject = current.getAsJsonObject();
            if (!currentObject.has(key)) {
                return null;
            }
            current = currentObject.get(key);
        }

        return current == null || current.isJsonNull() ? null : current;
    }

    public static String string(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static String stringPath(JsonObject source, String... keys) {
        JsonElement value = elementPath(source, keys);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static Integer integer(JsonObject source, String key) {
        JsonElement value = source == null ? null : source.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static Integer integerPath(JsonObject source, String... keys) {
        JsonElement value = elementPath(source, keys);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static Boolean boolPath(JsonObject source, String... keys) {
        JsonElement value = elementPath(source, keys);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    public static JsonObject requiredObject(JsonObject source, String key) {
        return Objects.requireNonNull(object(source, key), "missing object: " + key);
    }

    public static JsonArray requiredArray(JsonObject source, String key) {
        return Objects.requireNonNull(array(source, key), "missing array: " + key);
    }
}
