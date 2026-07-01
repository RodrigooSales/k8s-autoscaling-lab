package org.csajava.runtime.evaluate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.util.Config;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.csajava.model.ResultError;
import org.csajava.runtime.initialdata.InitialDataStore;
import org.csajava.util.CpuQuantity;
import org.csajava.util.JsonUtil;
import org.csajava.util.YamlMap;

public final class EvaluateRuntime {
    public static final String STRATEGY_REPLICAS = "adapt_replicas";
    public static final String STRATEGY_TAG = "adapt_tag";
    public static final String STRATEGY_CPU = "adapt_cpu";

    public static final String PARAM_CPU_MULTIPLIER = "cpu_multiplier";
    public static final String PARAM_TAG_UP = "tag_up";
    public static final String PARAM_UPDATE_CPU = "update_cpu";

    private static final Gson GSON = new Gson();

    private EvaluateRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        JsonObject stdin = context.stdinJson();
        JsonObject resource = JsonUtil.object(stdin, "resource");
        JsonObject resourceSpec = JsonUtil.object(resource, "spec");
        Integer currentReplicas = JsonUtil.integer(resourceSpec, "replicas");
        if (currentReplicas == null) {
            return new ResultError("error", "missing resource.spec.replicas");
        }

        JsonObject resourceMetadata = JsonUtil.object(resource, "metadata");
        String deploymentName = JsonUtil.string(resourceMetadata, "name");
        String deploymentNamespace = JsonUtil.string(resourceMetadata, "namespace");
        if (deploymentName == null || deploymentNamespace == null) {
            return new ResultError("error", "missing resource.metadata name/namespace");
        }

        JsonObject metricPayload = extractMetricPayload(stdin);
        if (metricPayload == null) {
            return new ResultError("error", "missing metrics[0].value payload");
        }

        Integer currentValue = parseMetricToInt(metricPayload, "current_value");
        Integer targetValue = parseMetricToInt(metricPayload, "target_value");
        if (currentValue == null || targetValue == null || targetValue == 0) {
            return new ResultError("error", "invalid metric values");
        }

        double rate = ((double) currentValue) / ((double) targetValue * 1000.0);

        Map<String, Object> config = context.config();
        List<String> enabledStrategies = enabledStrategies(config);
        int minReplicas = intOrDefault(config, "minReplicas", 1);
        int maxReplicas = intOrDefault(config, "maxReplicas", 10);
        int maxCpu = intOrDefault(config, "maxCPU", 1000);

        RuntimeContext hinted = context.withHints(resolveHints(context, deploymentName, deploymentNamespace));
        int currentMcpu = intOrDefault(hinted.hints(), "current_mcpu", 0);
        int initialMcpu = intOrDefault(hinted.hints(), "initial_mcpu", 0);
        int desiredReplicas = (int) Math.ceil(currentReplicas * rate);

        if (rate >= 0.95d) {
            if (enabledStrategies.contains(STRATEGY_REPLICAS) && currentReplicas < maxReplicas) {
                desiredReplicas = Math.min(desiredReplicas, maxReplicas);
                return buildEvaluation(STRATEGY_REPLICAS, mapOf("replicas", desiredReplicas));
            }
            if (enabledStrategies.contains(STRATEGY_CPU) && currentMcpu < maxCpu) {
                return buildEvaluation(STRATEGY_CPU, mapOf(PARAM_CPU_MULTIPLIER, rate));
            }
            if (enabledStrategies.contains(STRATEGY_TAG)) {
                return buildEvaluation(
                        STRATEGY_TAG,
                        mapOf(
                                PARAM_TAG_UP, false,
                                PARAM_UPDATE_CPU, enabledStrategies.contains(STRATEGY_CPU)));
            }
        }

        if (rate < 0.90d) {
            if (enabledStrategies.contains(STRATEGY_REPLICAS) && currentReplicas > minReplicas) {
                desiredReplicas = Math.max(desiredReplicas, minReplicas);
                return buildEvaluation(STRATEGY_REPLICAS, mapOf("replicas", desiredReplicas));
            }
            if (enabledStrategies.contains(STRATEGY_CPU) && currentMcpu > initialMcpu) {
                return buildEvaluation(STRATEGY_CPU, mapOf(PARAM_CPU_MULTIPLIER, rate));
            }
            if (enabledStrategies.contains(STRATEGY_TAG)) {
                return buildEvaluation(STRATEGY_TAG, mapOf(PARAM_TAG_UP, true));
            }
        }

        return null;
    }

    private static JsonObject extractMetricPayload(JsonObject stdin) {
        JsonArray metrics = JsonUtil.array(stdin, "metrics");
        if (metrics == null || metrics.isEmpty() || !metrics.get(0).isJsonObject()) {
            return null;
        }

        JsonObject firstMetric = metrics.get(0).getAsJsonObject();
        String metricValue = JsonUtil.string(firstMetric, "value");
        if (metricValue == null || metricValue.isBlank()) {
            return null;
        }

        try {
            return GSON.fromJson(metricValue, JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer parseMetricToInt(JsonObject payload, String key) {
        String value = JsonUtil.string(payload, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseQuantityInt(value);
    }

    private static int intOrDefault(Map<String, Object> source, String key, int defaultValue) {
        Integer value = YamlMap.integer(source, key);
        return value == null ? defaultValue : value;
    }

    private static List<String> enabledStrategies(Map<String, Object> config) {
        List<Object> raw = YamlMap.list(config, "enabled_strategies");
        if (raw.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            fallback.add(STRATEGY_REPLICAS);
            return fallback;
        }

        List<String> parsed = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String strategy) {
                parsed.add(strategy);
            }
        }
        if (parsed.isEmpty()) {
            parsed.add(STRATEGY_REPLICAS);
        }
        return parsed;
    }

    private static JsonObject buildEvaluation(String strategy, Map<String, Object> params) {
        JsonObject out = new JsonObject();
        out.addProperty("strategy", strategy);
        out.add("parameters", GSON.toJsonTree(params));
        return out;
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        return Map.of(key, normalizeRate(value));
    }

    private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        return Map.of(
                key1, normalizeRate(value1),
                key2, normalizeRate(value2));
    }

    private static Object normalizeRate(Object value) {
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                return d;
            }
            return BigDecimal.valueOf(d).setScale(12, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
        }
        return value;
    }

    private static Integer parseQuantityInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        try {
            if (value.endsWith("m")) {
                String base = value.substring(0, value.length() - 1);
                BigDecimal milli = new BigDecimal(base);
                BigDecimal units = milli.divide(BigDecimal.valueOf(1000), 9, RoundingMode.DOWN);
                return units.intValue();
            }

            if (value.endsWith("n")) {
                String base = value.substring(0, value.length() - 1);
                BigDecimal nano = new BigDecimal(base);
                BigDecimal units = nano.divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.DOWN);
                return units.intValue();
            }

            if (value.endsWith("k") || value.endsWith("K")) {
                String base = value.substring(0, value.length() - 1);
                BigDecimal number = new BigDecimal(base).multiply(BigDecimal.valueOf(1000));
                return number.intValue();
            }

            if (value.endsWith("M")) {
                String base = value.substring(0, value.length() - 1);
                BigDecimal number = new BigDecimal(base).multiply(BigDecimal.valueOf(1_000_000));
                return number.intValue();
            }

            if (value.endsWith("G")) {
                String base = value.substring(0, value.length() - 1);
                BigDecimal number = new BigDecimal(base).multiply(BigDecimal.valueOf(1_000_000_000L));
                return number.intValue();
            }

            if (value.endsWith("Ki") || value.endsWith("Mi") || value.endsWith("Gi")) {
                String suffix = value.endsWith("Ki") ? "Ki" : value.endsWith("Mi") ? "Mi" : "Gi";
                String base = value.substring(0, value.length() - suffix.length());
                BigDecimal factor = switch (suffix) {
                    case "Ki" -> BigDecimal.valueOf(1024L);
                    case "Mi" -> BigDecimal.valueOf(1024L * 1024L);
                    default -> BigDecimal.valueOf(1024L * 1024L * 1024L);
                };
                return new BigDecimal(base).multiply(factor).intValue();
            }

            return new BigDecimal(value).intValue();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Object> resolveHints(RuntimeContext context, String name, String namespace) {
        if (!context.hints().isEmpty()) {
            return context.hints();
        }

        try {
            ApiClient client = Config.fromCluster();
            AppsV1Api apps = new AppsV1Api(client);
            CoreV1Api core = new CoreV1Api(client);
            CustomObjectsApi customObjects = new CustomObjectsApi(client);

            V1Deployment deployment = apps.readNamespacedDeployment(name, namespace).execute();
            if (deployment == null) {
                return Map.of();
            }

            Integer currentMcpu = readCurrentMcpu(core, namespace, deployment);
            InitialDataStore store = new InitialDataStore(context, core, customObjects);
            Integer initialMcpu = store.getStoredCpuLimit();
            if (initialMcpu == null || initialMcpu <= 0) {
                initialMcpu = readSpecMcpu(deployment);
                store.storeCpuLimit(initialMcpu);
            }

            return Map.of(
                    "current_mcpu", currentMcpu == null ? 0 : currentMcpu,
                    "initial_mcpu", initialMcpu == null ? 0 : initialMcpu);
        } catch (ApiException e) {
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Integer readSpecMcpu(V1Deployment deployment) {
        V1DeploymentSpec spec = deployment.getSpec();
        if (spec == null || spec.getTemplate() == null || spec.getTemplate().getSpec() == null) {
            return null;
        }
        List<V1Container> containers = spec.getTemplate().getSpec().getContainers();
        if (containers == null) {
            return null;
        }
        for (V1Container container : containers) {
            Integer mcpu = readContainerMcpu(container);
            if (mcpu != null) {
                return mcpu;
            }
        }
        return null;
    }

    private static Integer readCurrentMcpu(CoreV1Api core, String namespace, V1Deployment deployment)
            throws ApiException {
        String labelSelector = buildSelector(deployment);
        V1PodList pods = core.listNamespacedPod(namespace).labelSelector(labelSelector).execute();
        if (pods == null || pods.getItems() == null || pods.getItems().isEmpty()) {
            return null;
        }

        Integer max = null;
        for (V1Pod pod : pods.getItems()) {
            if (pod == null || pod.getSpec() == null || pod.getSpec().getContainers() == null
                    || pod.getSpec().getContainers().isEmpty()) {
                continue;
            }
            V1Container first = pod.getSpec().getContainers().get(0);
            Integer mcpu = readContainerMcpu(first);
            if (mcpu != null && (max == null || mcpu > max)) {
                max = mcpu;
            }
        }
        return max;
    }

    private static Integer readContainerMcpu(V1Container container) {
        if (container == null) {
            return null;
        }
        V1ResourceRequirements resources = container.getResources();
        if (resources == null || resources.getLimits() == null || resources.getLimits().get("cpu") == null) {
            return null;
        }
        String cpu = resources.getLimits().get("cpu").toSuffixedString();
        return CpuQuantity.parseToMilli(cpu);
    }

    private static String buildSelector(V1Deployment deployment) {
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getSelector() == null
                || deployment.getSpec().getSelector().getMatchLabels() == null
                || deployment.getSpec().getSelector().getMatchLabels().isEmpty()) {
            return null;
        }
        StringBuilder selector = new StringBuilder();
        for (Map.Entry<String, String> entry : deployment.getSpec().getSelector().getMatchLabels().entrySet()) {
            if (selector.length() > 0) {
                selector.append(',');
            }
            selector.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return selector.toString();
    }

    static boolean rolloutInProgress(V1Deployment deployment) {
        if (deployment == null) {
            return false;
        }

        V1DeploymentStatus status = deployment.getStatus();
        V1DeploymentSpec spec = deployment.getSpec();
        V1ObjectMeta metadata = deployment.getMetadata();
        if (status == null || spec == null || metadata == null) {
            return false;
        }

        long observed = status.getObservedGeneration() == null ? 0L : status.getObservedGeneration();
        long desired = metadata.getGeneration() == null ? 0L : metadata.getGeneration();
        int specReplicas = spec.getReplicas() == null ? 0 : spec.getReplicas();
        int updated = status.getUpdatedReplicas() == null ? 0 : status.getUpdatedReplicas();
        int available = status.getAvailableReplicas() == null ? 0 : status.getAvailableReplicas();

        boolean complete = observed >= desired && updated == specReplicas && available == specReplicas;
        return !complete;
    }
}
