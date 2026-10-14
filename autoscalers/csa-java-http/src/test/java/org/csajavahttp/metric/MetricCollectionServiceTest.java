package org.csajavahttp.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MetricCollectionServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final MetricCollectionService service = new MetricCollectionService();

    @Test
    void extractsTheFirstRuntimeMetricAndPreservesCallbackValues() throws Exception {
        var spec = JSON.readTree("""
                {"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[
                  {"spec":{"external":{"target":{"value":"1000"}}},
                   "external":{"current":{"value":"950000"}}},
                  {"spec":{"external":{"target":{"value":"2000"}}},
                   "external":{"current":{"value":"999999"}}}
                ]}
                """);

        var snapshot = service.collect(spec);

        assertEquals(2, snapshot.currentReplicas());
        assertEquals(950000, snapshot.currentMetricValue().longValueExact());
        assertEquals(1000, snapshot.targetMetricValue().longValueExact());
        assertEquals("950000", snapshot.rawCurrentMetricValue());
        assertEquals("1000", snapshot.rawTargetMetricValue());
    }

    @Test
    void readsMetricValuesFromRuntimeEvaluationPayload() throws Exception {
        var spec = JSON.readTree("""
                {"resource":{"spec":{"replicas":3}},"metrics":[
                  {"value":"{\\"current_value\\":\\"950000\\",\\"target_value\\":\\"1000\\"}"}
                ]}
                """);

        var snapshot = service.snapshotFromEvaluation(spec);

        assertEquals(3, snapshot.currentReplicas());
        assertEquals(950000, snapshot.currentMetricValue().longValueExact());
        assertEquals(1000, snapshot.targetMetricValue().longValueExact());
    }

    @Test
    void rejectsMissingRuntimeMetrics() throws Exception {
        var spec = JSON.readTree("""
                {"resource":{"spec":{"replicas":1}},"kubernetesMetrics":[]}
                """);

        MetricCollectionException error = assertThrows(MetricCollectionException.class, () -> service.collect(spec));

        assertEquals("kubernetesMetrics must contain at least one metric", error.getMessage());
    }
}
