package org.csajavahttp.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class ReplicaAdaptationServiceTest {
    private static final String DEPLOYMENT_PATH =
            "/apis/apps/v1/namespaces/default/deployments/kube-znn";

    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void startApiServer() throws IOException {
        responseStatus = 200;
        responseBody = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopApiServer() {
        server.stop(0);
    }

    @Test
    void patchesOnlyDeploymentReplicasWithStrategicMergePatch() {
        service().adaptReplicas(4);

        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("PATCH " + DEPLOYMENT_PATH + " "));
        assertTrue(requests.get(0).contains("application/strategic-merge-patch+json"));
        assertTrue(requests.get(0).endsWith("{\"spec\":{\"replicas\":4}}"));
    }

    @Test
    void reportsDeploymentPatchFailure() {
        responseStatus = 403;
        responseBody = "forbidden";

        AdaptationException error = assertThrows(AdaptationException.class, () -> service().adaptReplicas(4));

        assertTrue(error.getMessage().contains("default/kube-znn"));
        assertEquals(1, requests.size());
    }

    private ReplicaAdaptationService service() {
        ApiClient client = new ApiClient().setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
        return new ReplicaAdaptationService("default", "kube-znn", client);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI()
                + " " + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);

        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
