package org.csajavahttp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.List;
import java.util.Map;
import org.csajavahttp.adapt.ReplicaAdaptationService;
import org.csajavahttp.metric.MetricCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class CallbackControllerTest {
    @Test
    void metricCallbackConsumesGenericRuntimePayload() throws Exception {
        mockMvc(mock(ReplicaAdaptationService.class)).perform(post("/metric")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[
                                  {"spec":{"external":{"target":{"value":"1000"}}},
                                   "external":{"current":{"value":"950000"}}}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current_replicas").value(2))
                .andExpect(jsonPath("$.target_value").value("1000"))
                .andExpect(jsonPath("$.current_value").value("950000"));
    }

    @Test
    void evaluationReturnsDecisionOrNoContent() throws Exception {
        MockMvc mockMvc = mockMvc(mock(ReplicaAdaptationService.class));

        mockMvc.perform(post("/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluationPayload("950000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("adapt_replicas"))
                .andExpect(jsonPath("$.parameters.replicas").value(2));

        mockMvc.perform(post("/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluationPayload("940000")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void replicaCallbackUsesTheEvaluatedCount() throws Exception {
        ReplicaAdaptationService replicaService = mock(ReplicaAdaptationService.class);

        mockMvc(replicaService).perform(post("/adapt/replicas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evaluation\":{\"parameters\":{\"replicas\":4}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replicas").value(4));

        verify(replicaService).adaptReplicas(4);
    }

    private static MockMvc mockMvc(ReplicaAdaptationService replicaService) {
        return MockMvcBuilders.standaloneSetup(new CallbackController(
                new MetricCollectionService(), replicaService, 1, 5, 750, "adapt_replicas", "")).build();
    }

    private static String evaluationPayload(String currentValue) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String metric = json.writeValueAsString(Map.of("current_value", currentValue, "target_value", "1000"));
        return json.writeValueAsString(Map.of(
                "resource", Map.of("spec", Map.of("replicas", 2)),
                "metrics", List.of(Map.of("value", metric))));
    }
}
