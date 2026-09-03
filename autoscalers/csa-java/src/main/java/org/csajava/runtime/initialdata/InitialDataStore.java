package org.csajava.runtime.initialdata;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.PatchUtils;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.csajava.context.RuntimeContext;

public final class InitialDataStore {
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
    private final String csaName;
    private final String csaNamespace;
    private final boolean hintsMode;

    public InitialDataStore(RuntimeContext context, CoreV1Api core, CustomObjectsApi custom) {
        this(context, core, custom, System.getenv("CSA_NAME"), System.getenv("CSA_NAMESPACE"));
    }

    InitialDataStore(
            RuntimeContext context,
            CoreV1Api core,
            CustomObjectsApi custom,
            String csaName,
            String csaNamespace) {
        this.context = context;
        this.core = core;
        this.custom = custom;
        this.csaName = csaName;
        this.csaNamespace = csaNamespace;
        this.hintsMode = !context.hints().isEmpty();
    }

    public void storeTag(String tag) {
        if (tag != null && !tag.isBlank()) {
            storeData(FIELD_TAG, tag);
        }
    }

    public String getStoredTag() {
        Object value = getStoredData(FIELD_TAG);
        return value == null ? null : String.valueOf(value);
    }

    public void storeCpuLimit(Integer cpuLimit) {
        if (cpuLimit != null) {
            storeData(FIELD_CPU_LIMIT, cpuLimit);
        }
    }

    public Integer getStoredCpuLimit() {
        Object value = getStoredCpuLimitValue();
        if (value == null || "".equals(value)) {
            return null;
        }
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    public Object getStoredCpuLimitValue() {
        return getStoredData(FIELD_CPU_LIMIT);
    }

    private Object getStoredData(String field) {
        if (hintsMode) {
            Object value = FIELD_TAG.equals(field)
                    ? context.hints().get("stored_initial_tag")
                    : context.hints().get("stored_initial_mcpu");
            return value == null ? "" : value;
        }

        Map<String, Object> status = getCsaStatus();
        if (status == null || !status.containsKey(STATUS_KEY)) {
            return "";
        }
        JsonObject initialData = parseInitialData(status.get(STATUS_KEY));
        if (!initialData.has(field)) {
            return "";
        }
        return primitiveValue(initialData.get(field));
    }

    private void storeData(String field, Object value) {
        Object stored = getStoredData(field);
        if (stored != null && !"".equals(stored)) {
            return;
        }
        if (!hintsMode) {
            setSelfAnnotation(field, value);
        }
    }

    private void setSelfAnnotation(String field, Object value) {
        V1Pod selfPod = getSelfPod();
        if (selfPod == null) {
            return;
        }

        JsonObject initialData = new JsonObject();
        Map<String, Object> status = getCsaStatus();
        if (status != null && status.containsKey(STATUS_KEY)) {
            initialData = parseInitialData(status.get(STATUS_KEY));
        }
        initialData.add(field, GSON.toJsonTree(value));

        if (selfPod.getMetadata().getAnnotations() == null) {
            selfPod.getMetadata().setAnnotations(new HashMap<>());
        }
        selfPod.getMetadata().getAnnotations().put(ANNOTATION_TAG, GSON.toJson(initialData));

        try {
            V1Patch patch = new V1Patch(JSON.serialize(selfPod));
            PatchUtils.patch(
                    V1Pod.class,
                    () -> core.patchNamespacedPod(csaName, csaNamespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    core.getApiClient());
        } catch (ApiException error) {
            throw new IllegalStateException("failed to patch CSA pod", error);
        }
    }

    private V1Pod getSelfPod() {
        if (!hasIdentity() || core == null) {
            return null;
        }
        try {
            return core.readNamespacedPod(csaName, csaNamespace).execute();
        } catch (ApiException error) {
            throw new IllegalStateException("failed to read CSA pod", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCsaStatus() {
        if (!hasIdentity() || custom == null) {
            return null;
        }
        try {
            Object raw = custom.getNamespacedCustomObjectStatus(
                            GROUP,
                            VERSION,
                            csaNamespace,
                            PLURAL,
                            csaName)
                    .execute();
            if (raw instanceof Map<?, ?> object) {
                Object status = object.get("status");
                if (status instanceof Map<?, ?> statusMap) {
                    return (Map<String, Object>) statusMap;
                }
            }
            return null;
        } catch (ApiException error) {
            throw new IllegalStateException("failed to read CSA status", error);
        }
    }

    private boolean hasIdentity() {
        return csaName != null && !csaName.isBlank() && csaNamespace != null && !csaNamespace.isBlank();
    }

    private static JsonObject parseInitialData(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("invalid status.initialData");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text);
        } catch (RuntimeException error) {
            throw new IllegalStateException("invalid status.initialData", error);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("invalid status.initialData");
        }
        return parsed.getAsJsonObject();
    }

    private static Object primitiveValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive()) {
            return GSON.fromJson(value, Object.class);
        }
        if (value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        BigDecimal number = value.getAsBigDecimal();
        try {
            return number.intValueExact();
        } catch (ArithmeticException ignored) {
            return number;
        }
    }
}
