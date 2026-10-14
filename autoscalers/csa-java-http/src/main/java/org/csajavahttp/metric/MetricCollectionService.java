package org.csajavahttp.metric;

import io.kubernetes.client.custom.Quantity;
import java.math.BigInteger;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class MetricCollectionService {
    private static final ObjectMapper JSON = new ObjectMapper();

    public MetricSnapshot collect(JsonNode spec) {
        JsonNode resource = objectField(spec, "resource");
        int replicas = replicaCount(objectField(resource, "spec"));
        JsonNode metrics = spec.path("kubernetesMetrics");
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw new MetricCollectionException("kubernetesMetrics must contain at least one metric");
        }

        JsonNode metric = metrics.get(0);
        JsonNode target = valueField(
                objectField(objectField(objectField(metric, "spec"), "external"), "target"), "value");
        JsonNode current = valueField(
                objectField(objectField(metric, "external"), "current"), "value");
        return snapshot(replicas, current, target);
    }

    public MetricSnapshot snapshotFromEvaluation(JsonNode spec) {
        JsonNode resource = objectField(spec, "resource");
        int replicas = replicaCount(objectField(resource, "spec"));
        JsonNode metrics = spec.path("metrics");
        if (!metrics.isArray() || metrics.isEmpty()) {
            throw new MetricCollectionException("metrics must contain at least one metric");
        }
        JsonNode metric = objectValue(metrics.get(0), "metrics[0]");
        JsonNode value = valueField(metric, "value");
        if (!value.isTextual()) {
            throw new MetricCollectionException("metrics[0].value must contain JSON text");
        }

        JsonNode payload;
        try {
            payload = JSON.readTree(value.asText());
        } catch (Exception error) {
            throw new MetricCollectionException("metrics[0].value contains invalid JSON", error);
        }
        return snapshot(
                replicas,
                valueField(payload, "current_value"),
                valueField(payload, "target_value"));
    }

    private static MetricSnapshot snapshot(int replicas, JsonNode current, JsonNode target) {
        String currentValue = quantityText(current, "current metric value");
        String targetValue = quantityText(target, "target metric value");
        return new MetricSnapshot(
                replicas,
                integerQuantity(currentValue, "current metric value"),
                integerQuantity(targetValue, "target metric value"),
                currentValue,
                targetValue);
    }

    private static int replicaCount(JsonNode resourceSpec) {
        JsonNode replicas = valueField(resourceSpec, "replicas");
        if (!replicas.canConvertToInt() || replicas.asInt() < 0) {
            throw new MetricCollectionException("resource.spec.replicas must be a non-negative integer");
        }
        return replicas.asInt();
    }

    private static JsonNode objectField(JsonNode object, String field) {
        JsonNode value = valueField(object, field);
        return objectValue(value, field);
    }

    private static JsonNode objectValue(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new MetricCollectionException(field + " must be an object");
        }
        return value;
    }

    private static JsonNode valueField(JsonNode object, String field) {
        if (object == null || !object.isObject() || !object.has(field) || object.get(field).isNull()) {
            throw new MetricCollectionException("missing field " + field);
        }
        return object.get(field);
    }

    private static String quantityText(JsonNode value, String field) {
        if (!value.isValueNode()) {
            throw new MetricCollectionException("Invalid " + field + ": " + value);
        }
        return value.asText();
    }

    private static BigInteger integerQuantity(String value, String field) {
        try {
            return new Quantity(value).getNumber().toBigInteger();
        } catch (RuntimeException error) {
            throw new MetricCollectionException("Invalid " + field + ": " + value, error);
        }
    }

    public record MetricSnapshot(
            int currentReplicas,
            BigInteger currentMetricValue,
            BigInteger targetMetricValue,
            String rawCurrentMetricValue,
            String rawTargetMetricValue) {
    }
}
