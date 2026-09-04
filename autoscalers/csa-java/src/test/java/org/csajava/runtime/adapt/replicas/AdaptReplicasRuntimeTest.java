package org.csajava.runtime.adapt.replicas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import java.io.IOException;
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

public class AdaptReplicasRuntimeTest {
    @Test
    public void scaleDeploymentReadsAndPatchesLoadedDeployment() throws Exception {
        List<String> trace = new ArrayList<>();
        List<JsonObject> bodies = new ArrayList<>();
        List<String> contentTypes = new ArrayList<>();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    trace.add(request.method() + " " + request.url().encodedPath());
                    if ("PATCH".equals(request.method())) {
                        contentTypes.add(request.body().contentType().toString());
                        Buffer buffer = new Buffer();
                        request.body().writeTo(buffer);
                        bodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
                    }
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(
                                    deploymentJson(),
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();

        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        AppsV1Api apps = new AppsV1Api(client);

        Object result = AdaptReplicasRuntime.adaptDeployment(
                contextWithParameters("{\"replicas\": 5}"), apps, client, "kube-znn", "default");

        assertEquals(5, ((JsonObject) result).get("replicas").getAsInt());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "PATCH /apis/apps/v1/namespaces/default/deployments/kube-znn"), trace);
        assertEquals(
                V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH,
                contentTypes.get(0));
        JsonObject body = bodies.get(0);
        assertEquals(5, body.getAsJsonObject("spec").get("replicas").getAsInt());
        assertEquals("7", body.getAsJsonObject("metadata").get("resourceVersion").getAsString());
        assertTrue(body.getAsJsonObject("spec").has("template"));
        assertFalse(body.getAsJsonObject("metadata").has("annotations"));
        assertFalse(body.getAsJsonObject("spec").getAsJsonObject("selector").has("matchExpressions"));
    }

    @Test
    public void invalidParametersReadDeploymentWithoutPatching() throws Exception {
        List<String> methods = new ArrayList<>();
        ApiClient client = client(request -> {
            methods.add(request.method());
            return deploymentJson();
        });
        AppsV1Api apps = new AppsV1Api(client);

        for (String parameters : List.of("{}", "{\"replicas\": \"3\"}", "{\"replicas\": 3.5}")) {
            assertEquals(null, AdaptReplicasRuntime.adaptDeployment(
                    contextWithParameters(parameters), apps, client, "kube-znn", "default"));
        }

        assertEquals(List.of("GET", "GET", "GET"), methods);
    }

    @Test
    public void booleanParameterFollowsPythonIntegerSemantics() throws Exception {
        List<JsonObject> bodies = new ArrayList<>();
        ApiClient client = client(request -> {
            if ("PATCH".equals(request.method())) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                bodies.add(JsonParser.parseString(buffer.readUtf8()).getAsJsonObject());
            }
            return deploymentJson();
        });

        JsonObject result = (JsonObject) AdaptReplicasRuntime.adaptDeployment(
                contextWithParameters("{\"replicas\": true}"),
                new AppsV1Api(client),
                client,
                "kube-znn",
                "default");

        assertEquals(true, bodies.get(0).getAsJsonObject("spec").get("replicas").getAsBoolean());
        assertEquals(true, result.get("replicas").getAsBoolean());
    }

    @Test
    public void apiFailuresFollowPythonOutputContract() throws Exception {
        RuntimeContext context = contextWithParameters("{\"replicas\": 5}");

        ApiClient readFailureClient = failingClient("GET");
        Object readFailure = AdaptReplicasRuntime.adaptDeployment(
                context,
                new AppsV1Api(readFailureClient),
                readFailureClient,
                "kube-znn",
                "default");
        assertEquals(null, readFailure);

        ApiClient patchFailureClient = failingClient("PATCH");
        Object patchFailure = AdaptReplicasRuntime.adaptDeployment(
                context,
                new AppsV1Api(patchFailureClient),
                patchFailureClient,
                "kube-znn",
                "default");
        assertEquals("error", ((JsonObject) patchFailure).get("result").getAsString());
        assertEquals(1, ((JsonObject) patchFailure).size());
    }

    private static RuntimeContext contextWithParameters(String parameters) {
        JsonObject stdin = JsonParser.parseString("""
                {
                  "resource": {"metadata": {"name": "kube-znn", "namespace": "default"}},
                  "evaluation": {"parameters": %s}
                }
                """.formatted(parameters)).getAsJsonObject();
        return new RuntimeContext(stdin.toString(), stdin, Map.of(), Map.of());
    }

    private static ApiClient client(ResponseContent responseContent) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(
                                    responseContent.forRequest(request),
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();
        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        return client;
    }

    private static ApiClient failingClient(String failingMethod) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    boolean fail = failingMethod.equals(request.method());
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(fail ? 500 : 200)
                            .message(fail ? "Failure" : "OK")
                            .body(ResponseBody.create(
                                    fail ? "{}" : deploymentJson(),
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();
        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.setHttpClient(httpClient);
        return client;
    }

    private static String deploymentJson() {
        return """
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "kube-znn",
                    "namespace": "default",
                    "resourceVersion": "7"
                  },
                  "spec": {
                    "replicas": 2,
                    "selector": {"matchLabels": {"app": "kube-znn"}},
                    "template": {
                      "metadata": {"labels": {"app": "kube-znn"}},
                      "spec": {"containers": [{"name": "znn", "image": "registry/znn:600k"}]}
                    }
                  }
                }
                """;
    }

    private interface ResponseContent {
        String forRequest(Request request) throws IOException;
    }
}
