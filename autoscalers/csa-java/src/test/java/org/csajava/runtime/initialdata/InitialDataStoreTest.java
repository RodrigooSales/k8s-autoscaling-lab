package org.csajava.runtime.initialdata;

import static org.junit.Assert.assertEquals;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
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
