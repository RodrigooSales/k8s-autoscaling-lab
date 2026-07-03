package org.csajava.runtime.initialdata;

import static org.junit.Assert.assertEquals;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

public class InitialDataStoreTest {
    @Test
    public void mergeSelfPodAnnotationPreservesExistingInitialData() {
        V1Pod pod = new V1Pod().metadata(new V1ObjectMeta()
                .annotations(Map.of(
                        "csa.custom-self-adapter.net/initialData",
                        "{\"tag\":\"600k\",\"cpu_limit\":\"250\"}")));

        Map<String, String> merged = InitialDataStore.mergeSelfPodAnnotation(
                pod, Map.of("cpu_limit", "500"));

        assertEquals("600k", merged.get("tag"));
        assertEquals("500", merged.get("cpu_limit"));
    }

    @Test
    public void podAnnotationUsesStrategicMergePatch() throws Exception {
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
                                    "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\"csa-znn\"}}",
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();

        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        CoreV1Api core = new CoreV1Api(client);

        InitialDataStore.patchPodAnnotation(
                core,
                "csa-znn",
                "default",
                new V1Patch("{\"metadata\":{\"annotations\":{\"initialData\":\"150\"}}}"));

        assertEquals(
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                captured.get().body().contentType().toString());
        assertEquals("/api/v1/namespaces/default/pods/csa-znn", captured.get().url().encodedPath());
    }
}
