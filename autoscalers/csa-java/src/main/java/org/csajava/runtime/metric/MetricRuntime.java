package org.csajava.runtime.metric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.csajava.context.RuntimeContext;
import org.csajava.model.ResultError;
import org.csajava.util.JsonUtil;

public final class MetricRuntime {
    private MetricRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        JsonObject stdin = context.stdinJson();

        JsonObject resource = JsonUtil.object(stdin, "resource");
        JsonObject resourceSpec = JsonUtil.object(resource, "spec");
        Integer currentReplicas = JsonUtil.integer(resourceSpec, "replicas");

        JsonArray kmetrics = JsonUtil.array(stdin, "kubernetesMetrics");
        if (kmetrics == null || kmetrics.isEmpty() || !kmetrics.get(0).isJsonObject()) {
            return new ResultError("error", "missing kubernetesMetrics[0]");
        }

        JsonObject firstMetric = kmetrics.get(0).getAsJsonObject();
        String targetValue = JsonUtil.stringPath(firstMetric, "spec", "external", "target", "value");
        String currentValue = JsonUtil.stringPath(firstMetric, "external", "current", "value");

        if (currentReplicas == null || targetValue == null || currentValue == null) {
            return new ResultError("error", "invalid metric input payload");
        }

        JsonObject out = new JsonObject();
        out.addProperty("current_replicas", currentReplicas);
        out.addProperty("target_value", targetValue);
        out.addProperty("current_value", currentValue);
        return out;
    }
}
