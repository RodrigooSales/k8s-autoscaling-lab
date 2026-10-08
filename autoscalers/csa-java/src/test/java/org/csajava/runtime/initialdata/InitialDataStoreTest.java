package org.csajava.runtime.initialdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.csajava.context.RuntimeContext;
import org.junit.Test;

public class InitialDataStoreTest {
    private static final String INITIAL_DATA = "csa.custom-self-adapter.net/initialData";

    @Test
    public void storesStatusAndNewFieldInTheLoadedPod() {
        InitialDataApi api = new InitialDataApi(List.of(
                "{\"cpu_limit\":\"500\"}",
                "{\"cpu_limit\":\"500\"}"));
        InitialDataStore store = store(api);

        store.storeTag("600k");

        assertEquals(List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "GET /api/v1/namespaces/default/pods/csa-znn",
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "PATCH /api/v1/namespaces/default/pods/csa-znn"), api.trace);
        assertEquals(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, api.patchContentType);
        assertEquals("11", api.patchBody.getAsJsonObject("metadata").get("resourceVersion").getAsString());
        assertTrue(api.patchBody.has("spec"));
        assertEquals("keep", api.patchBody.getAsJsonObject("metadata")
                .getAsJsonObject("annotations").get("unrelated").getAsString());
        assertEquals(
                "{\"cpu_limit\": \"500\", \"tag\": \"600k\"}",
                api.patchBody.getAsJsonObject("metadata")
                        .getAsJsonObject("annotations").get(INITIAL_DATA).getAsString());
        assertFalse(api.patchBody.getAsJsonObject("metadata").has("finalizers"));

        JsonObject initialData = patchedInitialData(api);
        assertEquals("500", initialData.get("cpu_limit").getAsString());
        assertEquals("600k", initialData.get("tag").getAsString());
        assertFalse(initialData.has("annotation_only"));
    }

    @Test
    public void logsStatusAndMergeLikePython() throws Exception {
        InitialDataApi api = new InitialDataApi(List.of(
                "{\"cpu_limit\":\"500\"}",
                "{\"cpu_limit\":\"500\"}"));
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        String originalPath = System.getProperty("csa.adapter.log");
        Path log = Files.createTempFile("csa-java-initial-data", ".log");
        try {
            System.setProperty("csa.adapter.log", log.toString());
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            store(api).storeTag("600k");

            String expected = """
                    initial_data -   INFO - {'initialData': '{"cpu_limit":"500"}'}
                    initial_data -   INFO - {'tag': '600k'}
                    initial_data -   INFO - {'cpu_limit': '500', 'tag': '600k'}
                    """;
            assertEquals(expected, normalize(stderr.toString(StandardCharsets.UTF_8)));
            assertEquals(expected, normalize(Files.readString(log)));
        } finally {
            if (originalPath == null) {
                System.clearProperty("csa.adapter.log");
            } else {
                System.setProperty("csa.adapter.log", originalPath);
            }
            System.setErr(originalErr);
        }
    }

    @Test
    public void readsStatusAgainAfterWritingInsteadOfUsingLocalCache() {
        InitialDataApi api = new InitialDataApi(List.of("{}", "{}", "{}"));
        InitialDataStore store = store(api);

        store.storeTag("600k");

        assertEquals("", store.getStoredTag());
        assertEquals(3, api.statusReads);
    }

    @Test
    public void existingStatusValueIsNeverOverwritten() {
        InitialDataApi api = new InitialDataApi(List.of("{\"tag\":\"600k\"}"));

        store(api).storeTag("400k");

        assertEquals(0, api.patches);
        assertEquals(List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status"),
                api.trace);
    }

    @Test
    public void reconciliationDelayCanLoseTheFirstSequentialWriteLikePython() {
        InitialDataApi api = new InitialDataApi(List.of("{}", "{}", "{}", "{}"));
        InitialDataStore store = store(api);

        store.storeTag("600k");
        store.storeCpuLimit(500);

        JsonObject initialData = patchedInitialData(api);
        assertEquals("500", initialData.get("cpu_limit").getAsString());
        assertFalse(initialData.has("tag"));
        assertEquals(2, api.patches);
    }

    @Test
    public void apiFailuresPropagateAtTheSameInitialDataSteps() {
        assertFailureTrace("STATUS_1", List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status"));
        assertFailureTrace("POD", List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "GET /api/v1/namespaces/default/pods/csa-znn"));
        assertFailureTrace("STATUS_2", List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "GET /api/v1/namespaces/default/pods/csa-znn",
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status"));
        assertFailureTrace("PATCH", List.of(
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "GET /api/v1/namespaces/default/pods/csa-znn",
                "GET /apis/custom-self-adapter.net/v1/namespaces/default/customselfadapters/csa-znn/status",
                "PATCH /api/v1/namespaces/default/pods/csa-znn"));
    }

    @Test
    public void missingIdentityIsIgnoredLikePython() {
        InitialDataApi api = new InitialDataApi(List.of());
        InitialDataStore store = new InitialDataStore(
                context(), new CoreV1Api(api.client), new CustomObjectsApi(api.client), null, null);

        store.storeTag("600k");

        assertEquals("", store.getStoredTag());
        assertTrue(api.trace.isEmpty());
    }

    private static void assertFailureTrace(String failingStep, List<String> expectedTrace) {
        InitialDataApi api = new InitialDataApi(List.of("{}", "{}"), failingStep);

        assertThrows(IllegalStateException.class, () -> store(api).storeTag("600k"));
        assertEquals(expectedTrace, api.trace);
    }

    private static InitialDataStore store(InitialDataApi api) {
        return new InitialDataStore(
                context(),
                new CoreV1Api(api.client),
                new CustomObjectsApi(api.client),
                "csa-znn",
                "default");
    }

    private static RuntimeContext context() {
        JsonObject stdin = new JsonObject();
        return new RuntimeContext("{}", stdin, Map.of(), Map.of());
    }

    private static JsonObject patchedInitialData(InitialDataApi api) {
        String annotation = api.patchBody.getAsJsonObject("metadata")
                .getAsJsonObject("annotations")
                .get(INITIAL_DATA)
                .getAsString();
        return JsonParser.parseString(annotation).getAsJsonObject();
    }

    private static String normalize(String value) {
        return value.replaceAll("(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} ", "");
    }

    private static final class InitialDataApi {
        private static final Gson GSON = new Gson();

        private final ApiClient client;
        private final List<String> statuses;
        private final String failingStep;
        private final List<String> trace = new ArrayList<>();
        private int statusReads;
        private int patches;
        private JsonObject patchBody;
        private String patchContentType;
        private String currentAnnotation = "{\"tag\":\"800k\",\"annotation_only\":\"value\"}";

        private InitialDataApi(List<String> statuses) {
            this(statuses, null);
        }

        private InitialDataApi(List<String> statuses, String failingStep) {
            this.statuses = statuses;
            this.failingStep = failingStep;
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(this::respond).build();
            client = new ApiClient();
            client.setBasePath("http://localhost");
            client.setHttpClient(http);
        }

        private Response respond(okhttp3.Interceptor.Chain chain) throws IOException {
            Request request = chain.request();
            String target = request.url().encodedPath();
            trace.add(request.method() + " " + target);

            boolean statusRequest = target.endsWith("/status");
            boolean podRead = "GET".equals(request.method()) && target.contains("/pods/");
            String step;
            if (statusRequest) {
                statusReads++;
                step = "STATUS_" + statusReads;
            } else if (podRead) {
                step = "POD";
            } else {
                step = "PATCH";
            }
            int status = step.equals(failingStep) ? 500 : 200;

            String body;
            if (status != 200) {
                body = "{}";
            } else if (statusRequest) {
                String value = statuses.isEmpty()
                        ? "{}"
                        : statuses.get(Math.min(statusReads - 1, statuses.size() - 1));
                JsonObject response = new JsonObject();
                JsonObject statusBody = new JsonObject();
                statusBody.addProperty("initialData", value);
                response.add("status", statusBody);
                body = response.toString();
            } else if (podRead) {
                body = podJson(currentAnnotation);
            } else {
                patches++;
                patchContentType = request.body().contentType().toString();
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                patchBody = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
                currentAnnotation = patchBody.getAsJsonObject("metadata")
                        .getAsJsonObject("annotations")
                        .get(INITIAL_DATA)
                        .getAsString();
                body = podJson(currentAnnotation);
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(status == 200 ? "OK" : "Failure")
                    .body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
        }

        private static String podJson(String annotation) {
            JsonObject pod = JsonParser.parseString("""
                    {
                      "apiVersion":"v1",
                      "kind":"Pod",
                      "metadata":{
                        "name":"csa-znn",
                        "namespace":"default",
                        "resourceVersion":"11",
                        "annotations":{"unrelated":"keep"}
                      },
                      "spec":{"containers":[{"name":"csa","image":"csa:latest"}]}
                    }
                    """).getAsJsonObject();
            pod.getAsJsonObject("metadata").getAsJsonObject("annotations").addProperty(INITIAL_DATA, annotation);
            return GSON.toJson(pod);
        }
    }
}
