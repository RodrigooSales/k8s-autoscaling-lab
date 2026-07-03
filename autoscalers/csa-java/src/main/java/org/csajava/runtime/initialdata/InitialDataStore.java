package org.csajava.runtime.initialdata;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.PatchUtils;
import java.util.HashMap;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.AdaptSupport;

public final class InitialDataStore {
    private static final String ENV_CSA_NAME = "CSA_NAME";
    private static final String ENV_CSA_NAMESPACE = "CSA_NAMESPACE";
    private static final String ANNOTATION_TAG = "csa.custom-self-adapter.net/initialData";
    private static final String STATUS_KEY = "initialData";

    private static final String GROUP = "custom-self-adapter.net";
    private static final String VERSION = "v1";
    private static final String PLURAL = "customselfadapters";

    private static final String FIELD_TAG = "tag";
    private static final String FIELD_CPU_LIMIT = "cpu_limit";

    private static final Gson GSON = new Gson();

    private final RuntimeContext context;
    private final CoreV1Api core;
    private final CustomObjectsApi custom;
    private final Map<String, String> local = new HashMap<>();
    private final boolean hintsMode;

    public InitialDataStore(RuntimeContext context, CoreV1Api core, CustomObjectsApi custom) {
        this.context = context;
        this.core = core;
        this.custom = custom;
        this.hintsMode = !context.hints().isEmpty();
    }

    public void storeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        storeData(FIELD_TAG, tag);
    }

    public String getStoredTag() {
        return getStoredData(FIELD_TAG);
    }

    public void storeCpuLimit(Integer cpuLimit) {
        if (cpuLimit == null) {
            return;
        }
        storeData(FIELD_CPU_LIMIT, String.valueOf(cpuLimit));
    }

    public Integer getStoredCpuLimit() {
        String raw = getStoredData(FIELD_CPU_LIMIT);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getStoredData(String field) {
        String cached = local.get(field);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        if (hintsMode) {
            String hintValue = hintValue(field);
            if (hintValue != null && !hintValue.isBlank()) {
                local.put(field, hintValue);
            }
            return hintValue == null ? "" : hintValue;
        }

        Map<String, Object> status = getCsaStatus();
        Map<String, Object> initialData = parseInitialData(status.get(STATUS_KEY));
        Object value = initialData.get(field);
        String stringValue = value == null ? "" : String.valueOf(value);
        if (!stringValue.isBlank()) {
            local.put(field, stringValue);
        }
        return stringValue;
    }

    private void storeData(String field, String value) {
        String stored = getStoredData(field);
        if (stored != null && !stored.isBlank()) {
            return;
        }

        local.put(field, value);
        if (hintsMode) {
            return;
        }

        patchSelfAnnotation(valueMap(field, value));
    }

    private Map<String, String> valueMap(String field, String value) {
        Map<String, String> map = new HashMap<>();

        Map<String, Object> status = getCsaStatus();
        Map<String, Object> initialData = parseInitialData(status.get(STATUS_KEY));
        for (Map.Entry<String, Object> entry : initialData.entrySet()) {
            if (entry.getValue() != null) {
                map.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        map.put(field, value);
        return map;
    }

    private void patchSelfAnnotation(Map<String, String> value) {
        String csaName = System.getenv(ENV_CSA_NAME);
        String csaNamespace = System.getenv(ENV_CSA_NAMESPACE);
        if (csaName == null || csaName.isBlank() || csaNamespace == null || csaNamespace.isBlank() || core == null) {
            return;
        }

        try {
            V1Pod selfPod = core.readNamespacedPod(csaName, csaNamespace).execute();
            if (selfPod == null || selfPod.getMetadata() == null) {
                return;
            }

            Map<String, String> mergedValue = mergeSelfPodAnnotation(selfPod, value);

            JsonObject patch = new JsonObject();
            JsonObject metadata = new JsonObject();
            JsonObject annotations = new JsonObject();
            annotations.addProperty(ANNOTATION_TAG, GSON.toJson(mergedValue));
            metadata.add("annotations", annotations);
            patch.add("metadata", metadata);

            patchPodAnnotation(core, csaName, csaNamespace, new V1Patch(GSON.toJson(patch)));
        } catch (ApiException e) {
            // Best effort only. Runtime logic continues even if annotation patch fails.
        }
    }

    static V1Pod patchPodAnnotation(CoreV1Api core, String name, String namespace, V1Patch patch)
            throws ApiException {
        return PatchUtils.patch(
                V1Pod.class,
                () -> core.patchNamespacedPod(name, namespace, patch).buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                core.getApiClient());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCsaStatus() {
        if (custom == null) {
            return Map.of();
        }

        String csaName = System.getenv(ENV_CSA_NAME);
        String csaNamespace = System.getenv(ENV_CSA_NAMESPACE);
        if (csaName == null || csaName.isBlank() || csaNamespace == null || csaNamespace.isBlank()) {
            return Map.of();
        }

        try {
            Object raw = custom.getNamespacedCustomObjectStatus(
                            GROUP,
                            VERSION,
                            csaNamespace,
                            PLURAL,
                            csaName)
                    .execute();

            if (raw instanceof Map<?, ?> map) {
                Object status = map.get("status");
                if (status instanceof Map<?, ?> statusMap) {
                    return (Map<String, Object>) statusMap;
                }
            }
            return Map.of();
        } catch (ApiException e) {
            return Map.of();
        }
    }

    static Map<String, String> mergeSelfPodAnnotation(V1Pod selfPod, Map<String, String> value) {
        Map<String, String> merged = new HashMap<>();
        if (selfPod != null && selfPod.getMetadata() != null && selfPod.getMetadata().getAnnotations() != null) {
            Map<String, Object> annotationData =
                    parseInitialData(selfPod.getMetadata().getAnnotations().get(ANNOTATION_TAG));
            for (Map.Entry<String, Object> entry : annotationData.entrySet()) {
                if (entry.getValue() != null) {
                    merged.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
        if (value != null) {
            merged.putAll(value);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInitialData(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                Object parsed = JsonParser.parseString(text);
                if (parsed instanceof JsonObject json) {
                    return GSON.fromJson(json, Map.class);
                }
            } catch (RuntimeException ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private String hintValue(String field) {
        if (FIELD_TAG.equals(field)) {
            String value = AdaptSupport.hintString(context, "stored_initial_tag");
            return value == null ? "" : value;
        }
        if (FIELD_CPU_LIMIT.equals(field)) {
            Integer value = AdaptSupport.hintInt(context, "stored_initial_mcpu");
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
