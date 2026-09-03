package org.csajava.runtime.metric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.csajava.context.RuntimeContext;

public final class MetricRuntime {
    private MetricRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        JsonObject stdin = context.stdinJson();
        JsonElement currentReplicas = required(stdin, "resource", "spec", "replicas");
        JsonElement targetValue = required(
                stdin, "kubernetesMetrics", "0", "spec", "external", "target", "value");
        JsonElement currentValue = required(
                stdin, "kubernetesMetrics", "0", "external", "current", "value");

        JsonObject out = new JsonObject();
        out.add("current_replicas", currentReplicas.deepCopy());
        out.add("target_value", targetValue.deepCopy());
        out.add("current_value", currentValue.deepCopy());
        return out;
    }

    private static JsonElement required(JsonObject source, String... path) {
        JsonElement current = source;
        for (String part : path) {
            if (current == null || current.isJsonNull()) {
                throw new IllegalArgumentException("missing metric input");
            }
            if (current.isJsonArray()) {
                int index;
                try {
                    index = Integer.parseInt(part);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("invalid metric input", error);
                }
                if (index >= current.getAsJsonArray().size()) {
                    throw new IllegalArgumentException("missing metric input");
                }
                current = current.getAsJsonArray().get(index);
            } else if (current.isJsonObject() && current.getAsJsonObject().has(part)) {
                current = current.getAsJsonObject().get(part);
            } else {
                throw new IllegalArgumentException("missing metric input");
            }
        }
        return current;
    }
}
