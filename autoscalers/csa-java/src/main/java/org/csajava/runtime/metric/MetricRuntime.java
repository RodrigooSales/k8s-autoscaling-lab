package org.csajava.runtime.metric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.csajava.context.RuntimeContext;
import org.csajava.logging.AdapterLogger;
import org.csajava.util.JsonUtil;

public final class MetricRuntime {
    private MetricRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        AdapterLogger logger = new AdapterLogger("metric");
        JsonObject stdin = context.stdinJson();
        logger.info("Starting metric script");

        JsonObject resource = JsonUtil.object(stdin, "resource");
        JsonObject resourceSpec = JsonUtil.object(resource, "spec");
        Integer currentReplicas = JsonUtil.integer(resourceSpec, "replicas");

        JsonArray kmetrics = JsonUtil.array(stdin, "kubernetesMetrics");
        if (kmetrics == null || kmetrics.isEmpty() || !kmetrics.get(0).isJsonObject()) {
            throw new IllegalArgumentException("missing kubernetesMetrics[0]");
        }

        JsonObject firstMetric = kmetrics.get(0).getAsJsonObject();
        String targetValue = JsonUtil.stringPath(firstMetric, "spec", "external", "target", "value");
        String currentValue = JsonUtil.stringPath(firstMetric, "external", "current", "value");

        if (currentReplicas == null || targetValue == null || currentValue == null) {
            throw new IllegalArgumentException("invalid metric input payload");
        }

        JsonObject out = new JsonObject();
        out.addProperty("current_replicas", currentReplicas);
        out.addProperty("target_value", targetValue);
        out.addProperty("current_value", currentValue);
        return out;
    }
}
