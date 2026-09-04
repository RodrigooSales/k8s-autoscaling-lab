package org.csajava.kubernetes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.openapi.JSON;
import java.util.ArrayList;
import java.util.List;

public final class KubernetesJson {
    private KubernetesJson() {
    }

    public static String serialize(Object value) {
        JsonElement json = JsonParser.parseString(JSON.serialize(value));
        removeEmptyDefaults(json);
        return json.toString();
    }

    private static void removeEmptyDefaults(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                removeEmptyDefaults(item);
            }
            return;
        }
        if (!value.isJsonObject()) {
            return;
        }

        JsonObject object = value.getAsJsonObject();
        List<String> empty = new ArrayList<>();
        for (String key : object.keySet()) {
            JsonElement child = object.get(key);
            removeEmptyDefaults(child);
            if (isEmpty(child)) {
                empty.add(key);
            }
        }
        empty.forEach(object::remove);
    }

    private static boolean isEmpty(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            return array.isEmpty();
        }
        return value.isJsonObject() && value.getAsJsonObject().isEmpty();
    }
}
