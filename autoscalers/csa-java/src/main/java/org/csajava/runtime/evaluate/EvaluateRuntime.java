package org.csajava.runtime.evaluate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.csajava.io.JsonOut;
import org.csajava.kubernetes.KubernetesClient;
import org.csajava.logging.AdapterLogger;
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
    private static final Map<Character, Integer> QUANTITY_EXPONENTS = Map.of(
            'n', -3,
            'u', -2,
            'm', -1,
            'K', 1,
            'k', 1,
            'M', 2,
            'G', 3,
            'T', 4,
            'P', 5,
            'E', 6);

    private EvaluateRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        new AdapterLogger("eval_main").info("evaluate main");
        AdapterLogger logger = new AdapterLogger("evaluate");
        JsonObject stdin = context.stdinJson();
        JsonObject resource = JsonUtil.object(stdin, "resource");
        JsonObject resourceSpec = JsonUtil.object(resource, "spec");
        Integer currentReplicas = JsonUtil.integer(resourceSpec, "replicas");
        if (currentReplicas == null) {
            throw new IllegalArgumentException("missing resource.spec.replicas");
        }

        JsonObject resourceMetadata = JsonUtil.object(resource, "metadata");
        String deploymentName = JsonUtil.string(resourceMetadata, "name");
        String deploymentNamespace = JsonUtil.string(resourceMetadata, "namespace");
        if (deploymentName == null || deploymentNamespace == null) {
            throw new IllegalArgumentException("missing resource.metadata name/namespace");
        }

        KubernetesState kubernetes = context.hints().isEmpty()
                ? loadKubernetesState(deploymentName, deploymentNamespace, logger)
                : null;

        logger.info("Starting evaluate script");

        Map<String, Object> config = context.loadConfig();
        logger.info("  loaded config");

        JsonObject metricPayload = extractMetricPayload(stdin);
        if (metricPayload == null) {
            throw new IllegalArgumentException("missing metrics[0].value payload");
        }
        logger.info("  loaded spec data");

        Object enabledStrategies = config.containsKey("enabled_strategies")
                ? config.get("enabled_strategies")
                : STRATEGY_REPLICAS;
        int minReplicas = intOrDefault(config, "minReplicas", 1);
        int maxReplicas = intOrDefault(config, "maxReplicas", 10);
        int maxCpu = intOrDefault(config, "maxCPU", 1000);
        logger.info("  parsed config data");

        BigInteger currentValue = parseMetricToInt(metricPayload, "current_value");
        BigInteger targetValue = parseMetricToInt(metricPayload, "target_value");
        if (targetValue.signum() == 0) {
            throw new ArithmeticException("target metric is zero");
        }

        BigInteger scaledTarget = targetValue.multiply(BigInteger.valueOf(1000));
        double rate = currentValue.doubleValue() / scaledTarget.doubleValue();
        logger.info("rate " + currentValue + " / " + scaledTarget + " = " + rate);
        logger.info("plan for rate " + rate + "; stragegies " + strategiesText(enabledStrategies));

        RuntimeContext hinted = context.withHints(resolveHints(context, kubernetes));
        Integer currentMcpuValue = YamlMap.integer(hinted.hints(), "current_mcpu");
        if (currentMcpuValue == null || currentMcpuValue == 0) {
            logger.error("Current CPU limit not found or unparsable");
            return null;
        }
        int currentMcpu = currentMcpuValue;
        int initialMcpu = intOrDefault(hinted.hints(), "initial_mcpu", 0);
        int desiredReplicas = (int) Math.ceil(currentReplicas * rate);

        if (rate >= 0.95d) {
            if (strategyEnabled(enabledStrategies, STRATEGY_REPLICAS) && currentReplicas < maxReplicas) {
                desiredReplicas = Math.min(desiredReplicas, maxReplicas);
                return loggedEvaluation(logger, STRATEGY_REPLICAS, mapOf("replicas", desiredReplicas));
            }
            if (strategyEnabled(enabledStrategies, STRATEGY_CPU) && currentMcpu < maxCpu) {
                return loggedEvaluation(logger, STRATEGY_CPU, mapOf(PARAM_CPU_MULTIPLIER, rate));
            }
            if (strategyEnabled(enabledStrategies, STRATEGY_TAG)) {
                return loggedEvaluation(
                        logger,
                        STRATEGY_TAG,
                        mapOf(
                                PARAM_TAG_UP, false,
                                PARAM_UPDATE_CPU, strategyEnabled(enabledStrategies, STRATEGY_CPU)));
            }
        }

        if (rate < 0.90d) {
            if (strategyEnabled(enabledStrategies, STRATEGY_REPLICAS) && currentReplicas > minReplicas) {
                desiredReplicas = Math.max(desiredReplicas, minReplicas);
                return loggedEvaluation(logger, STRATEGY_REPLICAS, mapOf("replicas", desiredReplicas));
            }
            if (strategyEnabled(enabledStrategies, STRATEGY_CPU) && currentMcpu > initialMcpu) {
                return loggedEvaluation(logger, STRATEGY_CPU, mapOf(PARAM_CPU_MULTIPLIER, rate));
            }
            if (strategyEnabled(enabledStrategies, STRATEGY_TAG)) {
                return loggedEvaluation(logger, STRATEGY_TAG, mapOf(PARAM_TAG_UP, true));
            }
        }

        logger.info("No adaptation selected");
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
            JsonObject payload = GSON.fromJson(metricValue, JsonObject.class);
            return payload;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid metrics[0].value payload", e);
        }
    }

    private static BigInteger parseMetricToInt(JsonObject payload, String key) {
        JsonElement value = payload == null ? null : payload.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("invalid metric value: " + key);
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return new BigDecimal(value.getAsDouble()).toBigInteger();
        }
        return parseQuantityInt(value.getAsString());
    }

    private static int intOrDefault(Map<String, Object> source, String key, int defaultValue) {
        Integer value = YamlMap.integer(source, key);
        return value == null ? defaultValue : value;
    }

    private static boolean strategyEnabled(Object configured, String strategy) {
        if (configured instanceof String text) {
            return text.contains(strategy);
        }
        if (configured instanceof List<?> list) {
            return list.contains(strategy);
        }
        if (configured instanceof Map<?, ?> map) {
            return map.containsKey(strategy);
        }
        throw new IllegalArgumentException("enabled_strategies is not iterable");
    }

    private static JsonObject buildEvaluation(String strategy, Map<String, Object> params) {
        JsonObject out = new JsonObject();
        out.addProperty("strategy", strategy);
        out.add("parameters", GSON.toJsonTree(params));
        return out;
    }

    private static JsonObject loggedEvaluation(
            AdapterLogger logger, String strategy, Map<String, Object> parameters) {
        JsonObject evaluation = buildEvaluation(strategy, parameters);
        logger.info(JsonOut.stringify(evaluation));
        return evaluation;
    }

    private static String strategiesText(Object strategies) {
        if (!(strategies instanceof List<?> values)) {
            return String.valueOf(strategies);
        }
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append('\'').append(values.get(index)).append('\'');
        }
        return text.append(']').toString();
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        return Map.of(key, value);
    }

    private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return values;
    }

    private static BigInteger parseQuantityInt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("invalid quantity");
        }

        String number = raw;
        String suffix = null;
        int length = raw.length();
        if (length >= 2 && raw.charAt(length - 1) == 'i'
                && QUANTITY_EXPONENTS.containsKey(raw.charAt(length - 2))) {
            number = raw.substring(0, length - 2);
            suffix = raw.substring(length - 2);
        } else if (length >= 1 && QUANTITY_EXPONENTS.containsKey(raw.charAt(length - 1))) {
            number = raw.substring(0, length - 1);
            suffix = raw.substring(length - 1);
        }

        BigDecimal parsed;
        try {
            parsed = new BigDecimal(number);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid number format: " + number, ex);
        }
        if (suffix == null) {
            return parsed.toBigInteger();
        }
        if ("ki".equals(suffix)) {
            throw new IllegalArgumentException(raw + " has unknown suffix");
        }

        int exponent = QUANTITY_EXPONENTS.get(suffix.charAt(0));
        BigDecimal quantity;
        if (suffix.endsWith("i")) {
            BigDecimal factor = BigDecimal.valueOf(1024).pow(Math.abs(exponent));
            quantity = exponent < 0 ? parsed.divide(factor) : parsed.multiply(factor);
        } else {
            quantity = parsed.scaleByPowerOfTen(exponent * 3);
        }
        return quantity.toBigInteger();
    }

    private static KubernetesState loadKubernetesState(String name, String namespace, AdapterLogger logger) {
        ApiClient client;
        try {
            client = KubernetesClient.load();
        } catch (Exception error) {
            logger.error("Failed to load in-cluster config: " + error);
            throw new IllegalStateException("failed to load Kubernetes config", error);
        }

        AppsV1Api apps = new AppsV1Api(client);
        try {
            V1Deployment deployment = apps.readNamespacedDeployment(name, namespace).execute();
            if (deployment == null) {
                logger.error("Deployment " + namespace + "/" + name + " not found");
                throw new IllegalStateException("deployment not found");
            }
            return new KubernetesState(
                    new CoreV1Api(client),
                    new CustomObjectsApi(client),
                    deployment,
                    namespace);
        } catch (ApiException error) {
            logger.error("Failed to read Deployment " + namespace + "/" + name + ": " + error);
            throw new IllegalStateException("failed to read deployment", error);
        }
    }

    private static Map<String, Object> resolveHints(RuntimeContext context, KubernetesState kubernetes) {
        if (!context.hints().isEmpty()) {
            return context.hints();
        }

        try {
            Integer currentMcpu = readCurrentMcpu(
                    kubernetes.core(), kubernetes.namespace(), kubernetes.deployment());
            Map<String, Object> state = new HashMap<>();
            if (currentMcpu == null || currentMcpu == 0) {
                return state;
            }
            state.put("current_mcpu", currentMcpu);

            InitialDataStore store = new InitialDataStore(context, kubernetes.core(), kubernetes.customObjects());
            Integer initialMcpu = store.getStoredCpuLimit();
            if (initialMcpu == null) {
                store.storeCpuLimit(readSpecMcpu(kubernetes.deployment()));
                state.put("initial_mcpu", 0);
            } else {
                state.put("initial_mcpu", initialMcpu);
            }
            return state;
        } catch (ApiException e) {
            throw new IllegalStateException("failed to resolve Kubernetes state", e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to resolve Kubernetes state", e);
        }
    }

    private record KubernetesState(
            CoreV1Api core,
            CustomObjectsApi customObjects,
            V1Deployment deployment,
            String namespace) {
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
