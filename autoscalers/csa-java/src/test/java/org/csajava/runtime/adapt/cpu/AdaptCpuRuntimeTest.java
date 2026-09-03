package org.csajava.runtime.adapt.cpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.initialdata.InitialDataStore;
import org.junit.Test;

public class AdaptCpuRuntimeTest {
    @Test
    public void roundsHalfToEvenLikePython() {
        assertEquals(154, AdaptCpuRuntime.adjustedMcpu(103, 1.5, 1, 1000));
        assertEquals(156, AdaptCpuRuntime.adjustedMcpu(311, 0.5, 1, 1000));
    }

    @Test
    public void preservesBaselineAndMaximumClamps() {
        assertEquals(500, AdaptCpuRuntime.adjustedMcpu(600, 0.5, 500, 1000));
        assertEquals(1000, AdaptCpuRuntime.adjustedMcpu(900, 2.0, 500, 1000));
    }

    @Test
    public void matchesPythonPodTraceOrderAndResizePayload() {
        CpuApi api = new CpuApi(null, false);
        RuntimeContext context = context();

        JsonObject result = (JsonObject) AdaptCpuRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));

        assertEquals(910, result.get("cpu").getAsInt());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn",
                "PATCH /api/v1/namespaces/default/pods/pod-a/resize",
                "PATCH /api/v1/namespaces/default/pods/pod-b/resize",
                "PATCH /api/v1/namespaces/default/pods/pod-c/resize"), api.trace);
        assertEquals(3, api.resizeBodies.size());
        for (JsonObject body : api.resizeBodies) {
            JsonArray containers = body.getAsJsonObject("spec").getAsJsonArray("containers");
            assertEquals("znn", containers.get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("nginx", containers.get(1).getAsJsonObject().get("name").getAsString());
            assertEquals("910m", containers.get(0).getAsJsonObject()
                    .getAsJsonObject("resources").getAsJsonObject("limits").get("cpu").getAsString());
            assertEquals(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, api.resizeContentTypes.get(0));
        }
    }

    @Test
    public void stopsAtTheFirstResizeFailure() {
        CpuApi firstFailure = runWithFailure("pod-a");
        assertEquals("error", firstFailure.result.get("result").getAsString());
        assertEquals(1, firstFailure.result.size());
        assertEquals(List.of("pod-a"), firstFailure.resizedPods);

        CpuApi lastFailure = runWithFailure("pod-c");
        assertEquals("error", lastFailure.result.get("result").getAsString());
        assertEquals(1, lastFailure.result.size());
        assertEquals(List.of("pod-a", "pod-b", "pod-c"), lastFailure.resizedPods);
    }

    @Test
    public void setupApiFailuresFollowPythonProcessContract() {
        CpuApi deploymentFailure = new CpuApi("DEPLOYMENT", false);
        RuntimeContext context = context();
        assertEquals(null, AdaptCpuRuntime.adaptInCluster(
                context,
                deploymentFailure.client,
                new AppsV1Api(deploymentFailure.client),
                new CoreV1Api(deploymentFailure.client),
                new InitialDataStore(context, null, null)));

        CpuApi listFailure = new CpuApi("LIST", false);
        assertThrows(IllegalStateException.class, () -> AdaptCpuRuntime.adaptInCluster(
                context,
                listFailure.client,
                new AppsV1Api(listFailure.client),
                new CoreV1Api(listFailure.client),
                new InitialDataStore(context, null, null)));
    }

    @Test
    public void doesNotReadCpuAgainWhenNoPodsExist() {
        CpuApi api = new CpuApi(null, true);
        RuntimeContext context = context();

        JsonObject result = (JsonObject) AdaptCpuRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));

        assertEquals("error", result.get("result").getAsString());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn"), api.trace);
    }

    @Test
    public void rolloutStopsAfterReadingDeployment() {
        CpuApi api = new CpuApi(null, false, true);
        RuntimeContext context = context();

        JsonObject result = (JsonObject) AdaptCpuRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));

        assertEquals("skip", result.get("result").getAsString());
        assertEquals(List.of("GET /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
    }

    @Test
    public void validatesMultiplierAfterReadingDeployment() {
        CpuApi api = new CpuApi(null, false);
        RuntimeContext context = context("\"high\"");

        JsonObject result = (JsonObject) AdaptCpuRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));

        assertEquals("error", result.get("result").getAsString());
        assertEquals(List.of("GET /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
    }

    @Test
    public void booleanMultiplierFollowsPythonNumberSemantics() {
        assertEquals(Double.valueOf(1.0), AdaptCpuRuntime.readMultiplier(context("true")));
        assertEquals(Double.valueOf(0.0), AdaptCpuRuntime.readMultiplier(context("false")));
    }

    @Test
    public void resizePodUsesStrategicMergePatch() throws Exception {
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
                                    "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\"znn\"}}",
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();

        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        CoreV1Api core = new CoreV1Api(client);

        AdaptCpuRuntime.resizePod(
                core,
                client,
                "znn",
                "default",
                new V1Patch("{\"spec\":{\"containers\":[{\"name\":\"znn\"}]}}"));

        assertEquals(
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                captured.get().body().contentType().toString());
        assertEquals("/api/v1/namespaces/default/pods/znn/resize", captured.get().url().encodedPath());
    }

    private static CpuApi runWithFailure(String failingPod) {
        CpuApi api = new CpuApi(failingPod, false);
        RuntimeContext context = context();
        api.result = (JsonObject) AdaptCpuRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));
        return api;
    }

    private static RuntimeContext context() {
        return context("1.3");
    }

    private static RuntimeContext context(String multiplier) {
        JsonObject stdin = JsonParser.parseString("""
                {
                  "resource": {"metadata": {"name": "kube-znn", "namespace": "default"}},
                  "evaluation": {"parameters": {"cpu_multiplier": %s}}
                }
                """.formatted(multiplier)).getAsJsonObject();
        return new RuntimeContext(
                stdin.toString(), stdin, Map.of("maxCPU", 1000), Map.of("stored_initial_mcpu", 500));
    }

    private static final class CpuApi {
        private final ApiClient client;
        private final String failingPod;
        private final boolean emptyPods;
        private final boolean rollout;
        private final List<String> trace = new ArrayList<>();
        private final List<String> resizedPods = new ArrayList<>();
        private final List<JsonObject> resizeBodies = new ArrayList<>();
        private final List<String> resizeContentTypes = new ArrayList<>();
        private int podLists;
        private JsonObject result;

        private CpuApi(String failingPod, boolean emptyPods) {
            this(failingPod, emptyPods, false);
        }

        private CpuApi(String failingPod, boolean emptyPods, boolean rollout) {
            this.failingPod = failingPod;
            this.emptyPods = emptyPods;
            this.rollout = rollout;
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(this::respond).build();
            client = new ApiClient();
            client.setBasePath("http://localhost");
            client.setHttpClient(http);
        }

        private Response respond(okhttp3.Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            String target = request.url().encodedPath();
            if (request.url().encodedQuery() != null) {
                target += "?" + request.url().encodedQuery();
            }
            trace.add(request.method() + " " + target);

            String body;
            int status = 200;
            if (target.contains("/deployments/")) {
                status = "DEPLOYMENT".equals(failingPod) ? 500 : 200;
                body = status == 200 ? deploymentJson(rollout) : "{}";
            } else if ("GET".equals(request.method()) && target.contains("/pods")) {
                podLists++;
                status = "LIST".equals(failingPod) ? 500 : 200;
                body = status == 200 ? podsJson(emptyPods, podLists > 1) : "{}";
            } else {
                String pod = request.url().pathSegments().get(5);
                resizedPods.add(pod);
                resizeContentTypes.add(request.body().contentType().toString());
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                resizeBodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
                if (pod.equals(failingPod)) {
                    status = 500;
                    body = "{}";
                } else {
                    body = "{\"apiVersion\":\"v1\",\"kind\":\"Pod\",\"metadata\":{\"name\":\"" + pod + "\"}}";
                }
            }
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(status == 200 ? "OK" : "Failure")
                    .body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
        }
    }

    private static String deploymentJson() {
        return deploymentJson(false);
    }

    private static String deploymentJson(boolean rollout) {
        return """
                {
                  "apiVersion":"apps/v1",
                  "kind":"Deployment",
                  "metadata":{"name":"kube-znn","namespace":"default","generation":3},
                  "spec":{
                    "replicas":2,
                    "selector":{"matchLabels":{"app":"kube-znn"}},
                    "template":{"spec":{"containers":[{"name":"znn","resources":{"limits":{"cpu":"500m"}}}]}}
                  },
                  "status":{"observedGeneration":%d,"updatedReplicas":2,"availableReplicas":2}
                }
                """.formatted(rollout ? 2 : 3);
    }

    private static String podsJson(boolean empty, boolean currentReading) {
        if (empty) {
            return "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[]}";
        }
        int[] cpus = currentReading ? new int[] {700, 600, 550} : new int[] {500, 500, 500};
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < cpus.length; index++) {
            if (index > 0) {
                items.append(',');
            }
            char suffix = (char) ('a' + index);
            items.append("{\"metadata\":{\"name\":\"pod-").append(suffix)
                    .append("\"},\"spec\":{\"containers\":[{\"name\":\"znn\",\"resources\":{\"limits\":{\"cpu\":\"")
                    .append(cpus[index]).append("m\"}}}]}}");
        }
        return "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[" + items + "]}";
    }
}
