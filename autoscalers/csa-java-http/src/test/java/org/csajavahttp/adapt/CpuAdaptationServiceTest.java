package org.csajavahttp.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.kubernetes.client.openapi.ApiClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CpuAdaptationServiceTest {
    private static final String DEPLOYMENT_PATH =
            "/apis/apps/v1/namespaces/default/deployments/kube-znn";
    private static final String POD_PATH = "/api/v1/namespaces/default/pods/kube-znn-pod/resize";
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private String currentCpu = "600m";
    private String initialData = "{\"cpu_limit\":500,\"tag\":\"100k\"}";
    private int responseStatus;

    @BeforeEach
    void startApiServer() throws IOException {
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopApiServer() {
        server.stop(0);
    }

    @Test
    void resizesEveryPodUsingConfiguredMaximumAndResizeSubresource() {
        AdaptationStateService state = state();

        var result = new CpuAdaptationService(state).adaptCpu(1.3, 750);

        assertEquals(750, result.get("cpu"));
        String resize = requests.stream().filter(request -> request.startsWith("PATCH " + POD_PATH)).findFirst()
                .orElseThrow(() -> new AssertionError("pods/resize was not called: " + requests));
        assertTrue(resize.contains("application/strategic-merge-patch+json"));
        assertTrue(resize.contains("\"cpu\":\"750m\""), resize);
    }

    @Test
    void doesNotReduceCpuBelowTheStoredInitialLimit() {
        AdaptationStateService state = state();

        var result = new CpuAdaptationService(state).adaptCpu(0.5, 750);

        assertEquals(500, result.get("cpu"));
        String resize = requests.stream().filter(request -> request.startsWith("PATCH " + POD_PATH)).findFirst()
                .orElseThrow(() -> new AssertionError("pods/resize was not called: " + requests));
        assertTrue(resize.contains("\"cpu\":\"500m\""));
    }

    @Test
    void evaluationStateReadsTheRunningCpuAndSavedBaseline() {
        var cpu = new CpuAdaptationService(state()).cpuState();

        assertEquals(new CpuAdaptationService.CpuState(600, 500), cpu);
        assertTrue(requests.stream().anyMatch(request -> request.startsWith("GET " + DEPLOYMENT_PATH)));
        assertTrue(requests.stream().anyMatch(request -> request.contains("customselfadapters/csa-test/status")));
    }

    @Test
    void storesInitialCpuWhenTheOperatorStatusHasNoBaseline() {
        initialData = "{}";
        AdaptationStateService state = state();

        int initial = state.initialMcpu(state.deployment());

        assertEquals(500, initial);
        String patch = requests.stream()
                .filter(request -> request.startsWith("PATCH /api/v1/namespaces/default/pods/csa-test "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("initialData annotation was not patched: " + requests));
        assertTrue(patch.contains("csa.custom-self-adapter.net/initialData"));
        assertTrue(patch.contains("cpu_limit\\\":500"));
    }

    private AdaptationStateService state() {
        ApiClient client = new ApiClient().setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
        return new AdaptationStateService(client, "default", "csa-test", "kube-znn");
    }

    private void respond(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                + exchange.getRequestHeaders().getFirst("Content-Type") + " " + body);
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestMethod().equals("GET") && path.equals(DEPLOYMENT_PATH)) {
            respond(exchange, deployment());
        } else if (exchange.getRequestMethod().equals("GET") && path.endsWith("customselfadapters/csa-test/status")) {
            respond(exchange, "{\"status\":{\"initialData\":\"" + initialData.replace("\"", "\\\"") + "\"}}");
        } else if (exchange.getRequestMethod().equals("GET") && path.equals("/api/v1/namespaces/default/pods")) {
            respond(exchange, podList());
        } else if (exchange.getRequestMethod().equals("PATCH") && path.equals(POD_PATH)) {
            respond(exchange, "{}");
        } else if (exchange.getRequestMethod().equals("PATCH")
                && path.equals("/api/v1/namespaces/default/pods/csa-test")) {
            respond(exchange, "{}");
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static String deployment() {
        return """
                {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"kube-znn","namespace":"default","generation":1},
                 "spec":{"replicas":1,"selector":{"matchLabels":{"app":"kube-znn"}},"template":{"spec":{"containers":[
                   {"name":"znn","image":"registry.k8s.lab/kube-znn:600k","resources":{"limits":{"cpu":"500m"}}},
                   {"name":"nginx","image":"nginx:stable","resources":{"limits":{"cpu":"500m"}}}]}}},
                 "status":{"observedGeneration":1,"updatedReplicas":1,"availableReplicas":1}}
                """;
    }

    private String podList() {
        return """
                {"apiVersion":"v1","kind":"PodList","items":[{"metadata":{"name":"kube-znn-pod","namespace":"default"},
                 "spec":{"containers":[{"name":"znn","resources":{"limits":{"cpu":"%s"}}},
                   {"name":"nginx","resources":{"limits":{"cpu":"%s"}}}]}}]}
                """.formatted(currentCpu, currentCpu);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }
}
