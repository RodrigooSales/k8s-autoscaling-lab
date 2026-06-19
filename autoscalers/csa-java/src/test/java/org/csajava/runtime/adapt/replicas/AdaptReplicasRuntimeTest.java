package org.csajava.runtime.adapt.replicas;

import static org.junit.Assert.assertEquals;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class AdaptReplicasRuntimeTest {
    @Test
    public void scaleDeploymentUsesStrategicMergePatch() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    captured.set(request);
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(
                                    "{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"metadata\":{\"name\":\"kube-znn\"}}",
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();

        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        AppsV1Api apps = new AppsV1Api(client);

        AdaptReplicasRuntime.scaleDeployment(
                apps,
                client,
                "kube-znn",
                "default",
                new V1Patch("{\"spec\":{\"replicas\":5}}"));

        assertEquals(
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                captured.get().body().contentType().toString());
        assertEquals("/apis/apps/v1/namespaces/default/deployments/kube-znn", captured.get().url().encodedPath());
    }
}
