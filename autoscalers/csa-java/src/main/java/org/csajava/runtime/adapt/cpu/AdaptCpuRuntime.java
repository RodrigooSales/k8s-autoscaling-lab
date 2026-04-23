package org.csajava.runtime.adapt.cpu;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import java.util.List;
import org.csajava.context.RuntimeContext;
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
        Double multiplier = readMultiplier(context);
        if (multiplier == null) {
            return AdaptSupport.error();
        }

        if (!context.hints().isEmpty()) {
            return evaluateFromHints(context, multiplier);
        }
        return evaluateInCluster(context, multiplier);
    }

    private static Object evaluateFromHints(RuntimeContext context, double multiplier) {
        if (AdaptSupport.hintBool(context, "rollout_in_progress", false)) {
            return AdaptSupport.skip();
        }

        int maxCpu = AdaptSupport.configInt(context, "maxCPU", 1000);
        InitialDataStore store = new InitialDataStore(context, null, null);

        Integer initialMcpu = store.getStoredCpuLimit();
        if (initialMcpu == null || initialMcpu <= 0) {
            Integer specMcpu = AdaptSupport.hintInt(context, "spec_mcpu");
            if (specMcpu == null || specMcpu <= 0) {
                return AdaptSupport.error();
            }
            store.storeCpuLimit(specMcpu);
            initialMcpu = specMcpu;
        }

        List<String> pods = AdaptSupport.hintStringList(context, "pods");
        if (pods.isEmpty()) {
            return AdaptSupport.error();
        }

        Integer currentMcpu = AdaptSupport.hintInt(context, "current_mcpu");
        if (currentMcpu == null || currentMcpu <= 0) {
            return AdaptSupport.error();
        }

        int newMcpu = adjustedMcpu(currentMcpu, multiplier, initialMcpu, maxCpu);
        boolean resized = AdaptSupport.hintBool(context, "pod_resize_success", true);
        if (!resized) {
            return AdaptSupport.error();
        }

        return AdaptSupport.cpu(newMcpu);
    }

    private static Object evaluateInCluster(RuntimeContext context, double multiplier) {
        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        if (name == null || namespace == null) {
            return AdaptSupport.error();
        }

        try {
            ApiClient client = Config.fromCluster();
            AppsV1Api apps = new AppsV1Api(client);
            CoreV1Api core = new CoreV1Api(client);
            CustomObjectsApi customObjects = new CustomObjectsApi(client);

            V1Deployment deployment = apps.readNamespacedDeployment(name, namespace).execute();
            if (deployment == null) {
                return AdaptSupport.error();
            }

            if (AdaptSupport.rolloutInProgress(deployment)) {
                return AdaptSupport.skip();
            }

            int maxCpu = AdaptSupport.configInt(context, "maxCPU", 1000);
            InitialDataStore store = new InitialDataStore(context, core, customObjects);

            Integer initialMcpu = store.getStoredCpuLimit();
            if (initialMcpu == null || initialMcpu <= 0) {
                Integer specMcpu = AdaptSupport.specMcpu(deployment);
                if (specMcpu == null || specMcpu <= 0) {
                    return AdaptSupport.error();
                }
                store.storeCpuLimit(specMcpu);
                initialMcpu = specMcpu;
            }

            List<V1Pod> pods = AdaptSupport.runningPods(core, namespace, deployment);
            if (pods.isEmpty()) {
                return AdaptSupport.error();
            }

            Integer currentMcpu = AdaptSupport.currentMcpu(pods);
            if (currentMcpu == null || currentMcpu <= 0) {
                return AdaptSupport.error();
            }

            int newMcpu = adjustedMcpu(currentMcpu, multiplier, initialMcpu, maxCpu);
            V1Patch resizePatch = buildResizePatch(newMcpu);

            for (V1Pod pod : pods) {
                if (pod == null || pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                    return AdaptSupport.error();
                }
                core.patchNamespacedPodResize(pod.getMetadata().getName(), namespace, resizePatch).execute();
            }

            return AdaptSupport.cpu(newMcpu);
        } catch (Exception e) {
            return AdaptSupport.error();
        }
    }

    static int adjustedMcpu(int currentMcpu, double multiplier, int initialMcpu, int maxCpu) {
        int newMcpu = (int) Math.round(currentMcpu * multiplier);
        if (newMcpu > maxCpu) {
            newMcpu = maxCpu;
        }
        if (newMcpu < initialMcpu) {
            newMcpu = initialMcpu;
        }
        return newMcpu;
    }

    static Double readMultiplier(RuntimeContext context) {
        JsonElement raw = JsonUtil.elementPath(context.stdinJson(), "evaluation", "parameters", PARAM_CPU_MULTIPLIER);
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
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
