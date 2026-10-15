package org.csajavahttp.adapt;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.util.PatchUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public class ReplicaAdaptationService {
    private final String namespace;
    private final String deploymentName;
    private final ApiClient client;
    private final AppsV1Api deploymentApi;

    @Autowired
    public ReplicaAdaptationService(
            ApiClient client,
            @Value("${csa.namespace}") String namespace,
            @Value("${csa.deployment-name}") String deploymentName) {
        this(namespace, deploymentName, client);
    }

    ReplicaAdaptationService(String namespace, String deploymentName, ApiClient client) {
        this.namespace = required(namespace, "namespace");
        this.deploymentName = required(deploymentName, "deployment name");
        this.client = client;
        this.deploymentApi = new AppsV1Api(client);
    }

    public void adaptReplicas(int desiredReplicas) {
        V1Patch patch = new V1Patch("{\"spec\":{\"replicas\":" + desiredReplicas + "}}");
        try {
            PatchUtils.patch(
                    V1Deployment.class,
                    () -> deploymentApi.patchNamespacedDeployment(deploymentName, namespace, patch).buildCall(null),
                    V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                    client);
        } catch (ApiException error) {
            throw new AdaptationException(
                    "Failed to patch Deployment " + namespace + "/" + deploymentName, error);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CSA " + name + " must not be blank");
        }
        return value;
    }
}
