package org.csajavahttp.adapt;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.util.PatchUtils;
import java.lang.reflect.Type;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public final class AdaptationStateService {
    private static final String GROUP = "custom-self-adapter.net";
    private static final String VERSION = "v1";
    private static final String PLURAL = "customselfadapters";
    private static final String INITIAL_DATA = "initialData";
    private static final String INITIAL_DATA_ANNOTATION = "csa.custom-self-adapter.net/initialData";
    private static final Gson JSON = new Gson();
    private static final Type INITIAL_DATA_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final String namespace;
    private final String csaName;
    private final String deploymentName;
    private final ApiClient client;
    private final AppsV1Api deployments;
    private final CoreV1Api core;
    private final CustomObjectsApi customObjects;

    @Autowired
    public AdaptationStateService(
            ApiClient client,
            @Value("${CSA_NAMESPACE:${csa.namespace:default}}") String namespace,
            @Value("${CSA_NAME:}") String csaName,
            @Value("${csa.deployment-name}") String deploymentName) {
        this(client, namespace, csaName, deploymentName, new AppsV1Api(client), new CoreV1Api(client), new CustomObjectsApi(client));
    }

    AdaptationStateService(
            ApiClient client,
            String namespace,
            String csaName,
            String deploymentName,
            AppsV1Api deployments,
            CoreV1Api core,
            CustomObjectsApi customObjects) {
        this.client = client;
        this.namespace = required(namespace, "namespace");
        this.csaName = required(csaName, "CSA name");
        this.deploymentName = required(deploymentName, "Deployment name");
        this.deployments = deployments;
        this.core = core;
        this.customObjects = customObjects;
    }

    public V1Deployment deployment() {
        try {
            return deployments.readNamespacedDeployment(deploymentName, namespace).execute();
        } catch (ApiException error) {
            throw new AdaptationException("Failed to read Deployment " + namespace + "/" + deploymentName, error);
        }
    }

    public List<V1Pod> pods(V1Deployment deployment) {
        Map<String, String> labels = deployment.getSpec().getSelector().getMatchLabels();
        String selector = labels == null ? "" : labels.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        try {
            V1PodList result = core.listNamespacedPod(namespace).labelSelector(selector).execute();
            return result.getItems() == null ? List.of() : result.getItems();
        } catch (ApiException error) {
            throw new AdaptationException(
                    "Failed to list Pods for Deployment " + namespace + "/" + deploymentName, error);
        }
    }

    public Integer currentMcpu(List<V1Pod> pods) {
        Integer current = null;
        for (V1Pod pod : pods) {
            List<V1Container> containers = pod.getSpec().getContainers();
            if (containers == null || containers.isEmpty() || containers.get(0).getResources() == null) {
                continue;
            }
            Map<String, Quantity> limits = containers.get(0).getResources().getLimits();
            Integer value = quantityMcpu(limits == null ? null : limits.get("cpu"));
            if (value != null && (current == null || value > current)) {
                current = value;
            }
        }
        return current;
    }

    public int initialMcpu(V1Deployment deployment) {
        Object stored = storedData("cpu_limit");
        if (stored == null || "".equals(stored) || isZero(stored)) {
            int cpu = deploymentMcpu(deployment);
            if (cpu == 0) {
                throw new AdaptationException("No CPU limits found in the Deployment spec", null);
            }
            storeData("cpu_limit", cpu);
            return cpu;
        }
        try {
            return stored instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(stored));
        } catch (NumberFormatException error) {
            throw new AdaptationException("Invalid stored cpu_limit", error);
        }
    }

    public Integer currentMcpu(V1Deployment deployment) {
        return currentMcpu(pods(deployment));
    }

    public boolean rolloutInProgress(V1Deployment deployment) {
        var metadata = deployment.getMetadata();
        var spec = deployment.getSpec();
        var status = deployment.getStatus();
        long generation = metadata.getGeneration() == null ? 0 : metadata.getGeneration();
        long observed = status == null || status.getObservedGeneration() == null ? 0 : status.getObservedGeneration();
        int replicas = spec.getReplicas() == null ? 0 : spec.getReplicas();
        int updated = status == null || status.getUpdatedReplicas() == null ? 0 : status.getUpdatedReplicas();
        int available = status == null || status.getAvailableReplicas() == null ? 0 : status.getAvailableReplicas();
        return !(observed >= generation && updated == replicas && available == replicas);
    }

    public Object storedData(String field) {
        Map<String, Object> status = csaStatus();
        Object serialized = status == null ? null : status.get(INITIAL_DATA);
        if (serialized == null || "".equals(serialized)) {
            return null;
        }
        if (!(serialized instanceof String text)) {
            throw new AdaptationException("status.initialData must be JSON text", null);
        }
        Map<String, Object> data;
        try {
            data = JSON.fromJson(text, INITIAL_DATA_TYPE);
        } catch (RuntimeException error) {
            throw new AdaptationException("Invalid status.initialData JSON", error);
        }
        if (data == null) {
            throw new AdaptationException("status.initialData must contain a JSON object", null);
        }
        return data.get(field);
    }

    public void storeData(String field, Object value) {
        Object stored = storedData(field);
        if (stored != null && !"".equals(stored)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> status = csaStatus();
        Object serialized = status == null ? null : status.get(INITIAL_DATA);
        if (serialized instanceof String text && !text.isBlank()) {
            try {
                Map<String, Object> previous = JSON.fromJson(text, INITIAL_DATA_TYPE);
                if (previous == null) {
                    throw new AdaptationException("status.initialData must contain a JSON object", null);
                }
                data.putAll(previous);
            } catch (RuntimeException error) {
                if (error instanceof AdaptationException adaptationError) {
                    throw adaptationError;
                }
                throw new AdaptationException("Invalid status.initialData JSON", error);
            }
        }
        data.put(field, value);
        String annotation = JSON.toJson(data);
        String patchBody = JSON.toJson(Map.of(
                "metadata", Map.of("annotations", Map.of(INITIAL_DATA_ANNOTATION, annotation))));
        try {
            V1Patch patch = new V1Patch(patchBody);
            PatchUtils.patch(
                    V1Pod.class,
                    () -> core.patchNamespacedPod(csaName, namespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
        } catch (ApiException error) {
            throw new AdaptationException("Failed to store initialData on CSA Pod " + namespace + "/" + csaName, error);
        }
    }

    public void resize(V1Pod pod, int cpu) {
        String patchBody = "{\"spec\":{\"containers\":["
                + "{\"name\":\"znn\",\"resources\":{\"limits\":{\"cpu\":\"" + cpu + "m\"}}},"
                + "{\"name\":\"nginx\",\"resources\":{\"limits\":{\"cpu\":\"" + cpu + "m\"}}}]}}";
        try {
            V1Patch patch = new V1Patch(patchBody);
            PatchUtils.patch(
                    V1Pod.class,
                    () -> core.patchNamespacedPodResize(pod.getMetadata().getName(), namespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
        } catch (ApiException error) {
            throw new AdaptationException(
                    "Failed to resize Pod " + namespace + "/" + pod.getMetadata().getName(), error);
        }
    }

    public void patchDeployment(String patchBody) {
        try {
            V1Patch patch = new V1Patch(patchBody);
            PatchUtils.patch(
                    V1Deployment.class,
                    () -> deployments.patchNamespacedDeployment(deploymentName, namespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
        } catch (ApiException error) {
            throw new AdaptationException("Failed to patch Deployment " + namespace + "/" + deploymentName, error);
        }
    }

    public int deploymentMcpu(V1Deployment deployment) {
        List<V1Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        for (V1Container container : containers) {
            V1ResourceRequirements resources = container.getResources();
            Integer cpu = quantityMcpu(resources == null || resources.getLimits() == null
                    ? null
                    : resources.getLimits().get("cpu"));
            if (cpu != null) {
                return cpu;
            }
        }
        return 0;
    }

    private Map<String, Object> csaStatus() {
        try {
            Object response = customObjects.getNamespacedCustomObjectStatus(
                            GROUP, VERSION, namespace, PLURAL, csaName)
                    .execute();
            if (!(response instanceof Map<?, ?> object) || !(object.get("status") instanceof Map<?, ?> status)) {
                return null;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            status.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        } catch (ApiException error) {
            throw new AdaptationException("Failed to read CustomSelfAdapter status " + namespace + "/" + csaName, error);
        }
    }

    private static Integer quantityMcpu(Quantity quantity) {
        if (quantity == null) {
            return null;
        }
        try {
            return quantity.getNumber().movePointRight(3).setScale(0, RoundingMode.HALF_EVEN).intValueExact();
        } catch (ArithmeticException error) {
            throw new AdaptationException("Invalid CPU quantity " + quantity, error);
        }
    }

    private static boolean isZero(Object value) {
        return value instanceof Number number && number.doubleValue() == 0;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CSA " + name + " must not be blank");
        }
        return value;
    }
}
