package org.csajavahttp.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class MetricCollectionServiceTest {
    private static final String DEPLOYMENT_PATH = "/apis/apps/v1/namespaces/default/deployments/kube-znn";
    private static final String METRIC_PATH = "/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95";

    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private String metricResponse;

    @BeforeEach
    void startApiServer() throws IOException {
        metricResponse = metricList("1200.0001", "300");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String requestUri = exchange.getRequestURI().toString();
            requests.add(requestUri);

            String response = requestUri.startsWith(DEPLOYMENT_PATH)
                    ? deployment()
                    : requestUri.startsWith(METRIC_PATH) ? metricResponse : "{}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopApiServer() {
        server.stop(0);
    }

    @Test
    void readsDeploymentAndSumsAllExternalMetricSeriesInMilliUnits() throws Exception {
        MetricCollectionService service = service();

        var snapshot = service.collect();

        assertEquals(3, snapshot.currentReplicas());
        assertEquals(1500001, snapshot.currentMetricValue().longValueExact());
        assertEquals(1000, snapshot.targetMetricValue().longValueExact());
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).startsWith(METRIC_PATH + "?labelSelector=region%3Dnorth"));
        assertEquals(DEPLOYMENT_PATH, requests.get(1));
    }

    @Test
    void reportsMissingExternalMetricValues() {
        metricResponse = metricList();

        MetricCollectionException error = assertThrows(MetricCollectionException.class, () -> service().collect());

        assertTrue(error.getMessage().contains("no values"));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith(METRIC_PATH));
    }

    private MetricCollectionService service() throws Exception {
        ApiClient client = new ApiClient().setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
        return new MetricCollectionService(
                "default", "kube-znn", "znn_latency_ms_p95", "region=north", "1000", client);
    }

    private static String deployment() {
        return """
                {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"kube-znn","namespace":"default"},
                 "spec":{"replicas":3,"selector":{"matchLabels":{"app":"kube-znn"}},
                 "template":{"metadata":{"labels":{"app":"kube-znn"}},"spec":{"containers":[{"name":"znn","image":"znn:test"}]}}}}
                """;
    }

    private static String metricList(String... values) {
        StringBuilder items = new StringBuilder();
        for (String value : values) {
            if (!items.isEmpty()) {
                items.append(',');
            }
            items.append("{\"metricName\":\"znn_latency_ms_p95\",\"timestamp\":\"2026-10-13T00:00:00Z\",\"value\":\"")
                    .append(value)
                    .append("\"}");
        }
        return "{\"apiVersion\":\"external.metrics.k8s.io/v1beta1\",\"kind\":\"ExternalMetricValueList\",\"items\":["
                + items + "]}";
    }
}
