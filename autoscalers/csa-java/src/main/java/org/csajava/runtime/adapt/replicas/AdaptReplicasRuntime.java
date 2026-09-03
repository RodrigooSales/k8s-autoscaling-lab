package org.csajava.runtime.adapt.replicas;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.AdaptSupport;

public final class AdaptReplicasRuntime {
    private AdaptReplicasRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        if (!context.hints().isEmpty()) {
            JsonElement replicas = replicaParameter(context);
            if (replicas == null) {
                return null;
            }
            boolean patchSuccess = AdaptSupport.hintBool(context, "deployment_patch_success", true);
            return patchSuccess ? replicaResult(replicas) : AdaptSupport.error();
        }

        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        if (name == null || namespace == null) {
            return null;
        }

        try {
            ApiClient client = Config.fromCluster();
            AppsV1Api apps = new AppsV1Api(client);

            return adaptDeployment(context, apps, client, name, namespace);
        } catch (Exception e) {
            return null;
        }
    }

    static Object adaptDeployment(
            RuntimeContext context, AppsV1Api apps, ApiClient client, String name, String namespace) {
        V1Deployment deployment;
        try {
            deployment = apps.readNamespacedDeployment(name, namespace).execute();
        } catch (ApiException error) {
            return null;
        }
        JsonElement replicas = replicaParameter(context);
        if (replicas == null) {
            return null;
        }

        JsonObject body;
        if (replicas.getAsJsonPrimitive().isBoolean()) {
            body = JsonParser.parseString(JSON.serialize(deployment)).getAsJsonObject();
            body.getAsJsonObject("spec").add("replicas", replicas.deepCopy());
        } else {
            deployment.getSpec().setReplicas(replicas.getAsInt());
            body = JsonParser.parseString(JSON.serialize(deployment)).getAsJsonObject();
        }

        try {
            PatchUtils.patch(
                    V1Deployment.class,
                    () -> apps.patchNamespacedDeployment(name, namespace, new V1Patch(body.toString()))
                            .buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
        } catch (ApiException error) {
            return AdaptSupport.error();
        }
        return replicaResult(replicas);
    }

    private static JsonElement replicaParameter(RuntimeContext context) {
        JsonObject params = AdaptSupport.evaluationParameters(context);
        JsonElement value = params == null ? null : params.get("replicas");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value;
        }
        if (!value.getAsJsonPrimitive().isNumber() || !value.toString().matches("-?(0|[1-9][0-9]*)")) {
            return null;
        }
        return value;
    }

    private static JsonObject replicaResult(JsonElement replicas) {
        JsonObject result = new JsonObject();
        result.add("replicas", replicas.deepCopy());
        return result;
    }
}
