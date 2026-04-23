package org.csajava.runtime.adapt.replicas;

import com.google.gson.JsonObject;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.util.Config;
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
            apps.patchNamespacedDeployment(name, namespace, new V1Patch(patch)).execute();
            return AdaptSupport.replicas(replicas);
        } catch (Exception e) {
            return AdaptSupport.error();
        }
    }
}
