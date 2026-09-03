package org.csajava.runtime.adapt.tag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

public class AdaptTagRuntimeTest {
    @Test
    public void parsesRegistryWithPortLikePython() {
        String image = "registry.k8s.lab:5000/project/kube-znn:600k";

        assertEquals("600k", AdaptTagRuntime.extractTag(image));
        assertEquals(
                "registry.k8s.lab:5000/project/kube-znn:400k",
                AdaptTagRuntime.replaceTag(image, "400k"));
    }

    @Test
    public void rejectsDigestAndImageWithoutTagLikePython() {
        String digest = "registry.k8s.lab/project/kube-znn@sha256:abcdef";
        String untagged = "registry.k8s.lab/project/kube-znn";

        assertNull(AdaptTagRuntime.extractTag(digest));
        assertEquals(digest, AdaptTagRuntime.replaceTag(digest, "400k"));
        assertNull(AdaptTagRuntime.extractTag(untagged));
    }

    @Test
    public void parsesUnknownTagButCannotMoveIt() {
        assertEquals("latest", AdaptTagRuntime.extractTag("registry.k8s.lab/kube-znn:latest"));
        assertNull(AdaptTagRuntime.adjacentTag("latest", false));
    }

    @Test
    public void matchesPythonTraceAndDeploymentPatchWhenUpdatingCpu() {
        TagApi api = new TagApi("registry.k8s.lab:5000/project/kube-znn:600k", false, null);
        RuntimeContext context = context(false, true);

        JsonObject result = adapt(context, api);

        assertEquals("400k", result.get("tag").getAsString());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn",
                "PATCH /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
        assertEquals(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, api.patchContentType);

        JsonObject body = api.patchBody;
        assertEquals("9", body.getAsJsonObject("metadata").get("resourceVersion").getAsString());
        JsonArray containers = body.getAsJsonObject("spec")
                .getAsJsonObject("template")
                .getAsJsonObject("spec")
                .getAsJsonArray("containers");
        assertEquals(
                "registry.k8s.lab:5000/project/kube-znn:400k",
                containers.get(0).getAsJsonObject().get("image").getAsString());
        assertEquals("700m", cpu(containers.get(0).getAsJsonObject()));
        assertEquals("700m", cpu(containers.get(1).getAsJsonObject()));
    }

    @Test
    public void missingPodsDoNotPreventTagPatch() {
        TagApi api = new TagApi("registry.k8s.lab/kube-znn:600k", true, null);

        JsonObject result = adapt(context(false, true), api);

        assertEquals("400k", result.get("tag").getAsString());
        assertEquals("500m", cpu(api.containers().get(0).getAsJsonObject()));
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn",
                "PATCH /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
    }

    @Test
    public void updateCpuFalseDoesNotListPods() {
        TagApi api = new TagApi("registry.k8s.lab/kube-znn:600k", false, null);

        JsonObject result = adapt(context(false, false), api);

        assertEquals("400k", result.get("tag").getAsString());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "PATCH /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
    }

    @Test
    public void imageAndRolloutFailuresStopBeforePatch() {
        for (String image : List.of(
                "registry.k8s.lab/project/kube-znn@sha256:abcdef",
                "registry.k8s.lab/project/kube-znn")) {
            TagApi api = new TagApi(image, false, null);
            assertEquals("error", adapt(context(false, false), api).get("result").getAsString());
            assertEquals(List.of("GET /apis/apps/v1/namespaces/default/deployments/kube-znn"), api.trace);
        }

        TagApi unknown = new TagApi("registry.k8s.lab/kube-znn:latest", false, null);
        assertEquals("skip", adapt(context(false, false), unknown).get("result").getAsString());
        assertEquals(List.of("GET /apis/apps/v1/namespaces/default/deployments/kube-znn"), unknown.trace);

        TagApi rollout = new TagApi("registry.k8s.lab/kube-znn:600k", false, null, true);
        assertEquals("skip", adapt(context(false, false), rollout).get("result").getAsString());
        assertEquals(List.of("GET /apis/apps/v1/namespaces/default/deployments/kube-znn"), rollout.trace);
    }

    @Test
    public void apiFailureStopsAtFailingCall() {
        TagApi listFailure = new TagApi("registry.k8s.lab/kube-znn:600k", false, "LIST");
        assertEquals("error", adapt(context(false, true), listFailure).get("result").getAsString());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "GET /api/v1/namespaces/default/pods?labelSelector=app%3Dkube-znn"), listFailure.trace);

        TagApi patchFailure = new TagApi("registry.k8s.lab/kube-znn:600k", false, "PATCH");
        assertEquals("error", adapt(context(false, false), patchFailure).get("result").getAsString());
        assertEquals(List.of(
                "GET /apis/apps/v1/namespaces/default/deployments/kube-znn",
                "PATCH /apis/apps/v1/namespaces/default/deployments/kube-znn"), patchFailure.trace);
    }

    private static JsonObject adapt(RuntimeContext context, TagApi api) {
        return (JsonObject) AdaptTagRuntime.adaptInCluster(
                context,
                api.client,
                new AppsV1Api(api.client),
                new CoreV1Api(api.client),
                new InitialDataStore(context, null, null));
    }

    private static RuntimeContext context(boolean tagUp, boolean updateCpu) {
        JsonObject stdin = JsonParser.parseString("""
                {
                  "resource":{"metadata":{"name":"kube-znn","namespace":"default"}},
                  "evaluation":{"parameters":{"tag_up":%s,"update_cpu":%s}}
                }
                """.formatted(tagUp, updateCpu)).getAsJsonObject();
        return new RuntimeContext(
                stdin.toString(),
                stdin,
                Map.of(),
                Map.of("stored_initial_tag", "600k", "stored_initial_mcpu", 500));
    }

    private static String cpu(JsonObject container) {
        return container.getAsJsonObject("resources")
                .getAsJsonObject("limits")
                .get("cpu")
                .getAsString();
    }

    private static final class TagApi {
        private final ApiClient client;
        private final String image;
        private final boolean emptyPods;
        private final String failingCall;
        private final boolean rollout;
        private final List<String> trace = new ArrayList<>();
        private JsonObject patchBody;
        private String patchContentType;

        private TagApi(String image, boolean emptyPods, String failingCall) {
            this(image, emptyPods, failingCall, false);
        }

        private TagApi(String image, boolean emptyPods, String failingCall, boolean rollout) {
            this.image = image;
            this.emptyPods = emptyPods;
            this.failingCall = failingCall;
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

            boolean list = "GET".equals(request.method()) && target.contains("/pods");
            boolean patch = "PATCH".equals(request.method()) && target.contains("/deployments/");
            int status = (list && "LIST".equals(failingCall)) || (patch && "PATCH".equals(failingCall))
                    ? 500
                    : 200;
            String body;
            if (status != 200) {
                body = "{}";
            } else if (list) {
                body = podsJson(emptyPods);
            } else if (patch) {
                patchContentType = request.body().contentType().toString();
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                patchBody = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
                body = deploymentJson(image, rollout);
            } else {
                body = deploymentJson(image, rollout);
            }

            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(status == 200 ? "OK" : "Failure")
                    .body(ResponseBody.create(body, MediaType.get("application/json")))
                    .build();
        }

        private JsonArray containers() {
            return patchBody.getAsJsonObject("spec")
                    .getAsJsonObject("template")
                    .getAsJsonObject("spec")
                    .getAsJsonArray("containers");
        }
    }

    private static String deploymentJson(String image, boolean rollout) {
        return """
                {
                  "apiVersion":"apps/v1",
                  "kind":"Deployment",
                  "metadata":{"name":"kube-znn","namespace":"default","generation":4,"resourceVersion":"9"},
                  "spec":{
                    "replicas":2,
                    "selector":{"matchLabels":{"app":"kube-znn"}},
                    "template":{"spec":{"containers":[
                      {"name":"znn","image":"%s","resources":{"limits":{"cpu":"500m"}}},
                      {"name":"nginx","image":"nginx:1.27","resources":{"limits":{"cpu":"500m"}}}
                    ]}}
                  },
                  "status":{"observedGeneration":%d,"updatedReplicas":2,"availableReplicas":2}
                }
                """.formatted(image, rollout ? 3 : 4);
    }

    private static String podsJson(boolean empty) {
        if (empty) {
            return "{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[]}";
        }
        return """
                {
                  "apiVersion":"v1",
                  "kind":"PodList",
                  "items":[{"metadata":{"name":"pod-a"},"spec":{"containers":[
                    {"name":"znn","resources":{"limits":{"cpu":"700m"}}}
                  ]}}]
                }
                """;
    }
}
