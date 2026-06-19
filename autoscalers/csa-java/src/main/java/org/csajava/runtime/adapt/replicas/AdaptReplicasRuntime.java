package org.csajava.runtime.adapt.replicas;

import com.google.gson.JsonObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.PatchUtils;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.AdaptSupport;
import org.csajava.util.JsonUtil;

public final class AdaptReplicasRuntime {
    private AdaptReplicasRuntime() {
    }

    public static Object evaluate(RuntimeContext context) {
        JsonObject params = AdaptSupport.evaluationParameters(context);
        Integer replicas = JsonUtil.integer(params, "replicas");
        if (replicas == null) {
            return AdaptSupport.error();
        }

        if (!context.hints().isEmpty()) {
            boolean patchSuccess = AdaptSupport.hintBool(context, "deployment_patch_success", true);
            return patchSuccess ? AdaptSupport.replicas(replicas) : AdaptSupport.error();
        }

        String name = AdaptSupport.resourceName(context);
        String namespace = AdaptSupport.resourceNamespace(context);
        if (name == null || namespace == null) {
            return AdaptSupport.error();
        }

        try {
            ApiClient client = Config.fromCluster();
            AppsV1Api apps = new AppsV1Api(client);

            String patch = "{\"spec\":{\"replicas\":" + replicas + "}}";
            scaleDeployment(apps, client, name, namespace, new V1Patch(patch));
            return AdaptSupport.replicas(replicas);
        } catch (ApiException e) {
            return AdaptSupport.error(apiErrorMessage(e));
        } catch (Exception e) {
            return AdaptSupport.error(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    static V1Deployment scaleDeployment(AppsV1Api apps, ApiClient client, String name, String namespace, V1Patch patch)
            throws ApiException {
        return PatchUtils.patch(
                V1Deployment.class,
                () -> apps.patchNamespacedDeployment(name, namespace, patch).buildCall(null),
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                client);
    }

    private static String apiErrorMessage(ApiException error) {
        String responseBody = error.getResponseBody();
        if (responseBody == null || responseBody.isBlank()) {
            responseBody = error.getMessage();
        }
        return "Kubernetes API error " + error.getCode() + ": " + String.valueOf(responseBody);
    }
}
