package org.csajava.runtime.adapt;

import com.google.gson.JsonObject;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.csajava.util.CpuQuantity;
import org.csajava.util.JsonUtil;
import org.csajava.util.YamlMap;

public final class AdaptSupport {
    private AdaptSupport() {
    }

    public static String resourceName(RuntimeContext context) {
        return JsonUtil.stringPath(context.stdinJson(), "resource", "metadata", "name");
    }

    public static String resourceNamespace(RuntimeContext context) {
        return JsonUtil.stringPath(context.stdinJson(), "resource", "metadata", "namespace");
    }

    public static JsonObject evaluationParameters(RuntimeContext context) {
        return JsonUtil.objectPath(context.stdinJson(), "evaluation", "parameters");
    }

    public static boolean rolloutInProgress(V1Deployment deployment) {
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

    public static List<V1Pod> runningPods(CoreV1Api core, String namespace, V1Deployment deployment)
            throws ApiException {
        String selector = labelSelector(deployment);
        V1PodList list = core.listNamespacedPod(namespace).labelSelector(selector).execute();
        if (list == null || list.getItems() == null) {
            return List.of();
        }
        return list.getItems();
    }

    public static String labelSelector(V1Deployment deployment) {
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

    public static Integer specMcpu(V1Deployment deployment) {
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null
                || deployment.getSpec().getTemplate().getSpec().getContainers() == null) {
            return null;
        }

        for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            Integer parsed = containerMcpu(container);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    public static Integer currentMcpu(List<V1Pod> pods) {
        if (pods == null || pods.isEmpty()) {
            return null;
        }

        Integer max = null;
        for (V1Pod pod : pods) {
            if (pod == null || pod.getSpec() == null || pod.getSpec().getContainers() == null
                    || pod.getSpec().getContainers().isEmpty()) {
                continue;
            }

            V1Container first = pod.getSpec().getContainers().get(0);
            Integer parsed = containerMcpu(first);
            if (parsed != null && (max == null || parsed > max)) {
                max = parsed;
            }
        }
        return max;
    }

    public static Integer containerMcpu(V1Container container) {
        if (container == null || container.getResources() == null || container.getResources().getLimits() == null
                || container.getResources().getLimits().get("cpu") == null) {
            return null;
        }
        Quantity cpu = container.getResources().getLimits().get("cpu");
        return CpuQuantity.parseToMilli(cpu.toSuffixedString());
    }

    public static V1Container findContainer(V1Deployment deployment, String name) {
        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null
                || deployment.getSpec().getTemplate().getSpec().getContainers() == null) {
            return null;
        }

        for (V1Container container : deployment.getSpec().getTemplate().getSpec().getContainers()) {
            if (container != null && name.equals(container.getName())) {
                return container;
            }
        }
        return null;
    }

    public static void ensureContainerCpuLimit(V1Container container, int mcpu) {
        if (container == null) {
            return;
        }

        V1ResourceRequirements resources = container.getResources();
        if (resources == null) {
            resources = new V1ResourceRequirements();
            container.setResources(resources);
        }

        Map<String, Quantity> limits = resources.getLimits();
        if (limits == null) {
            limits = new HashMap<>();
            resources.setLimits(limits);
        }

        limits.put("cpu", new Quantity(CpuQuantity.formatMilli(mcpu)));
    }

    public static int configInt(RuntimeContext context, String key, int defaultValue) {
        Integer parsed = YamlMap.integer(context.config(), key);
        return parsed == null ? defaultValue : parsed;
    }

    public static Integer hintInt(RuntimeContext context, String key) {
        return YamlMap.integer(context.hints(), key);
    }

    public static boolean hintBool(RuntimeContext context, String key, boolean defaultValue) {
        Boolean parsed = YamlMap.bool(context.hints(), key);
        return parsed == null ? defaultValue : parsed;
    }

    public static String hintString(RuntimeContext context, String key) {
        return YamlMap.string(context.hints(), key);
    }

    public static List<String> hintStringList(RuntimeContext context, String key) {
        List<Object> raw = YamlMap.list(context.hints(), key);
        if (raw.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    public static JsonObject result(String value) {
        JsonObject out = new JsonObject();
        out.addProperty("result", value);
        return out;
    }

    public static JsonObject error() {
        return result("error");
    }

    public static JsonObject error(String message) {
        JsonObject out = error();
        out.addProperty("message", message);
        return out;
    }

    public static JsonObject skip() {
        return result("skip");
    }

    public static JsonObject replicas(int replicas) {
        JsonObject out = new JsonObject();
        out.addProperty("replicas", replicas);
        return out;
    }

    public static JsonObject tag(String tag) {
        JsonObject out = new JsonObject();
        out.addProperty("tag", tag);
        return out;
    }

    public static JsonObject cpu(int mcpu) {
        JsonObject out = new JsonObject();
        out.addProperty("cpu", mcpu);
        return out;
    }
}
