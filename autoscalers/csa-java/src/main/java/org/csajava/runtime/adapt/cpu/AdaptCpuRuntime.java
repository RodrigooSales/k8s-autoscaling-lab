package org.csajava.runtime.adapt.cpu;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import java.util.List;
import org.csajava.context.RuntimeContext;
import org.csajava.logging.AdapterLogger;
import org.csajava.runtime.adapt.AdaptSupport;
import org.csajava.runtime.initialdata.InitialDataStore;
import org.csajava.util.CpuQuantity;
import org.csajava.util.JsonUtil;

public final class AdaptCpuRuntime {
    public static final String PARAM_CPU_MULTIPLIER = "cpu_multiplier";

    private static final Gson GSON = new Gson();

    private AdaptCpuRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        AdapterLogger logger = new AdapterLogger("adapt_cpu");
        if (!context.hints().isEmpty()) {
            Double multiplier = readMultiplier(context);
            logger.info("adapt_cpu for rate " + parameterText(context));
            if (multiplier == null) {
                logger.error("Parameter 'cpu_multiplier' must be a number; it's " + parameterText(context));
                return AdaptSupport.error();
            }
            return evaluateFromHints(context, multiplier, logger);
        }
        return evaluateInCluster(context, logger);
    }

    private static Object evaluateFromHints(RuntimeContext context, double multiplier, AdapterLogger logger) {
        if (AdaptSupport.hintBool(context, "rollout_in_progress", false)) {
            logger.info("Rollout in progress, skipping deployment patch");
            return AdaptSupport.skip();
        }

        int maxCpu = AdaptSupport.configInt(context.withConfig(context.loadConfig()), "maxCPU", 1000);
        InitialDataStore store = new InitialDataStore(context, null, null);

        Integer initialMcpu = store.getStoredCpuLimit();
        logger.info("Read initial_mcpu_data " + (initialMcpu == null ? "" : initialMcpu) + ".");
        if (initialMcpu == null || initialMcpu <= 0) {
            Integer specMcpu = AdaptSupport.hintInt(context, "spec_mcpu");
            logger.info("Read spec_mcpu " + specMcpu);
            if (specMcpu == null || specMcpu <= 0) {
                logger.error("No limits found in the containers specs!");
                return AdaptSupport.error();
            }
            logger.info("Storing initial cpu limit " + specMcpu);
            store.storeCpuLimit(specMcpu);
            initialMcpu = specMcpu;
        }

        List<String> pods = AdaptSupport.hintStringList(context, "pods");
        if (pods.isEmpty()) {
            logger.error("Could not find pods for deployment null in namespace null");
            return AdaptSupport.error();
        }

        Integer currentMcpu = AdaptSupport.hintInt(context, "current_mcpu");
        if (currentMcpu == null || currentMcpu <= 0) {
            logger.error("Current CPU limit not found or unparsable");
            return AdaptSupport.error();
        }

        int calculatedMcpu = roundedMcpu(currentMcpu, multiplier);
        logger.info("Calculated new_mcpu " + calculatedMcpu);
        int newMcpu = clampMcpu(calculatedMcpu, initialMcpu, maxCpu, logger);
        boolean resized = AdaptSupport.hintBool(context, "pod_resize_success", true);
        if (!resized) {
            logger.error("Failed to resize pod null/" + pods.get(0) + ": hinted failure");
            return AdaptSupport.error();
        }

        logger.info("Scaled cpu to " + newMcpu);
        return AdaptSupport.cpu(newMcpu);
    }

    private static Object evaluateInCluster(RuntimeContext context, AdapterLogger logger) {
        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        if (name == null || namespace == null) {
            logger.error("Spec must include resource.metadata.name and resource.metadata.namespace");
            return null;
        }

        ApiClient client;
        try {
            client = Config.fromCluster();
        } catch (Exception e) {
            logger.error("Failed to load in-cluster config: " + e);
            return null;
        }
        AppsV1Api apps = new AppsV1Api(client);
        CoreV1Api core = new CoreV1Api(client);
        CustomObjectsApi customObjects = new CustomObjectsApi(client);
        InitialDataStore store = new InitialDataStore(context, core, customObjects);
        return adaptInCluster(context, client, apps, core, store, logger);
    }

    static Object adaptInCluster(
            RuntimeContext context,
            ApiClient client,
            AppsV1Api apps,
            CoreV1Api core,
            InitialDataStore store) {
        return adaptInCluster(context, client, apps, core, store, new AdapterLogger("adapt_cpu"));
    }

    private static Object adaptInCluster(
            RuntimeContext context,
            ApiClient client,
            AppsV1Api apps,
            CoreV1Api core,
            InitialDataStore store,
            AdapterLogger logger) {
        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        V1Deployment deployment;
        try {
            deployment = apps.readNamespacedDeployment(name, namespace).execute();
        } catch (ApiException error) {
            logger.error("Failed to read Deployment " + namespace + "/" + name + ": " + error);
            return null;
        }
        if (deployment == null) {
            logger.error("Deployment " + namespace + "/" + name + " not found");
            return null;
        }

        Double multiplier = readMultiplier(context);
        logger.info("adapt_cpu for rate " + parameterText(context));
        if (multiplier == null) {
            logger.error("Parameter 'cpu_multiplier' must be a number; it's " + parameterText(context));
            return AdaptSupport.error();
        }

        if (AdaptSupport.rolloutInProgress(deployment)) {
            logger.info("Rollout in progress, skipping deployment patch");
            return AdaptSupport.skip();
        }

        int maxCpu = AdaptSupport.configInt(context.withConfig(context.loadConfig()), "maxCPU", 1000);

        Object initialMcpuData = store.getStoredCpuLimitValue();
        logger.info("Read initial_mcpu_data " + valueText(initialMcpuData) + ".");
        Integer initialMcpu = cpuLimit(initialMcpuData);
        if (initialMcpu == null || initialMcpu <= 0) {
            Integer specMcpu = AdaptSupport.specMcpu(deployment);
            logger.info("Read spec_mcpu " + specMcpu);
            if (specMcpu == null || specMcpu <= 0) {
                logger.error("No limits found in the containers specs!");
                return AdaptSupport.error();
            }
            logger.info("Storing initial cpu limit " + specMcpu);
            store.storeCpuLimit(specMcpu);
            initialMcpu = specMcpu;
        }

        List<V1Pod> pods = runningPods(core, namespace, deployment);
        if (pods.isEmpty()) {
            logger.error("Could not find pods for deployment " + name + " in namespace " + namespace);
            return AdaptSupport.error();
        }

        Integer currentMcpu = AdaptSupport.currentMcpu(runningPods(core, namespace, deployment));
        if (currentMcpu == null || currentMcpu <= 0) {
            logger.error("Current CPU limit not found or unparsable");
            return AdaptSupport.error();
        }

        int calculatedMcpu = roundedMcpu(currentMcpu, multiplier);
        logger.info("Calculated new_mcpu " + calculatedMcpu);
        int newMcpu = clampMcpu(calculatedMcpu, initialMcpu, maxCpu, logger);
        V1Patch resizePatch = buildResizePatch(newMcpu);

        for (V1Pod pod : pods) {
            if (pod == null || pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                logger.error("Failed to resize pod " + namespace + "/" + pod + ": missing pod name");
                return AdaptSupport.error();
            }
            try {
                resizePod(core, client, pod.getMetadata().getName(), namespace, resizePatch);
            } catch (ApiException error) {
                logger.error("Failed to resize pod " + namespace + "/" + pod + ": " + error);
                return AdaptSupport.error();
            }
        }

        logger.info("Scaled cpu to " + newMcpu);
        return AdaptSupport.cpu(newMcpu);
    }

    private static List<V1Pod> runningPods(CoreV1Api core, String namespace, V1Deployment deployment) {
        try {
            return AdaptSupport.runningPods(core, namespace, deployment);
        } catch (ApiException e) {
            throw new IllegalStateException("failed to list deployment pods", e);
        }
    }

    static V1Pod resizePod(CoreV1Api core, ApiClient client, String name, String namespace, V1Patch patch)
            throws ApiException {
        return PatchUtils.patch(
                V1Pod.class,
                () -> core.patchNamespacedPodResize(name, namespace, patch).buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                client);
    }

    static int adjustedMcpu(int currentMcpu, double multiplier, int initialMcpu, int maxCpu) {
        int newMcpu = roundedMcpu(currentMcpu, multiplier);
        if (newMcpu > maxCpu) {
            newMcpu = maxCpu;
        }
        if (newMcpu < initialMcpu) {
            newMcpu = initialMcpu;
        }
        return newMcpu;
    }

    private static int roundedMcpu(int currentMcpu, double multiplier) {
        return (int) Math.rint(currentMcpu * multiplier);
    }

    private static int clampMcpu(int newMcpu, int initialMcpu, int maxCpu, AdapterLogger logger) {
        if (newMcpu > maxCpu) {
            newMcpu = maxCpu;
            logger.info("new_mcpu capped to " + newMcpu);
        }
        if (newMcpu < initialMcpu) {
            newMcpu = initialMcpu;
            logger.info("new_mcpu capped to " + newMcpu);
        }
        return newMcpu;
    }

    private static Integer cpuLimit(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String parameterText(RuntimeContext context) {
        JsonElement value = JsonUtil.elementPath(
                context.stdinJson(), "evaluation", "parameters", PARAM_CPU_MULTIPLIER);
        return value == null || value.isJsonNull() ? "None" : value.getAsString();
    }

    private static String valueText(Object value) {
        return value == null ? "None" : String.valueOf(value);
    }

    static Double readMultiplier(RuntimeContext context) {
        JsonElement raw = JsonUtil.elementPath(context.stdinJson(), "evaluation", "parameters", PARAM_CPU_MULTIPLIER);
        if (raw == null || !raw.isJsonPrimitive()) {
            return null;
        }
        if (raw.getAsJsonPrimitive().isBoolean()) {
            return raw.getAsBoolean() ? 1.0 : 0.0;
        }
        if (!raw.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return raw.getAsDouble();
    }

    private static V1Patch buildResizePatch(int mcpu) {
        JsonObject patch = new JsonObject();
        JsonObject spec = new JsonObject();
        JsonArray containers = new JsonArray();

        containers.add(containerPatch("znn", mcpu));
        containers.add(containerPatch("nginx", mcpu));

        spec.add("containers", containers);
        patch.add("spec", spec);
        return new V1Patch(GSON.toJson(patch));
    }

    private static JsonObject containerPatch(String name, int mcpu) {
        JsonObject container = new JsonObject();
        JsonObject resources = new JsonObject();
        JsonObject limits = new JsonObject();

        container.addProperty("name", name);
        limits.addProperty("cpu", CpuQuantity.formatMilli(mcpu));
        resources.add("limits", limits);
        container.add("resources", resources);
        return container;
    }
}
