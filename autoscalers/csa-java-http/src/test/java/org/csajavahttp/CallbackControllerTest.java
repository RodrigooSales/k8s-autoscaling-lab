package org.csajavahttp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.List;
import java.util.Map;
import org.csajavahttp.adapt.CpuAdaptationService;
import org.csajavahttp.adapt.ReplicaAdaptationService;
import org.csajavahttp.adapt.TagAdaptationService;
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

    @Test
    void cpuCallbackIsAvailable() throws Exception {
        CpuAdaptationService cpuService = mock(CpuAdaptationService.class);
        when(cpuService.adaptCpu(1.2, 750)).thenReturn(Map.of("cpu", 600));
        mockMvc(mock(ReplicaAdaptationService.class), cpuService, mock(TagAdaptationService.class))
                .perform(post("/adapt/cpu")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evaluation\":{\"parameters\":{\"cpu_multiplier\":1.2}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpu").value(600));
        verify(cpuService).adaptCpu(1.2, 750);
    }

    @Test
    void tagCallbackPassesDirectionAndCpuSynchronization() throws Exception {
        TagAdaptationService tagService = mock(TagAdaptationService.class);
        when(tagService.adaptTag(false, true)).thenReturn(Map.of("tag", "400k"));

        mockMvc(mock(ReplicaAdaptationService.class), mock(CpuAdaptationService.class), tagService)
                .perform(post("/adapt/tag")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evaluation\":{\"parameters\":{\"tag_up\":false,\"update_cpu\":true}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag").value("400k"));
        verify(tagService).adaptTag(false, true);
    }

    @Test
    void evaluationUsesLiveCpuStateForScaleDown() throws Exception {
        CpuAdaptationService cpuService = mock(CpuAdaptationService.class);
        when(cpuService.cpuState()).thenReturn(new CpuAdaptationService.CpuState(600, 500));
        MockMvc mockMvc = mockMvc(
                mock(ReplicaAdaptationService.class), cpuService, mock(TagAdaptationService.class), "adapt_cpu", "");

        mockMvc.perform(post("/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(evaluationPayload("850000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("adapt_cpu"))
                .andExpect(jsonPath("$.parameters.cpu_multiplier").value(0.85));
        verify(cpuService).cpuState();
    }

    private static MockMvc mockMvc(ReplicaAdaptationService replicaService) {
        return mockMvc(replicaService, mock(CpuAdaptationService.class), mock(TagAdaptationService.class));
    }

    private static MockMvc mockMvc(
            ReplicaAdaptationService replicaService,
            CpuAdaptationService cpuService,
            TagAdaptationService tagService) {
        return mockMvc(replicaService, cpuService, tagService, "adapt_replicas", "");
    }

    private static MockMvc mockMvc(
            ReplicaAdaptationService replicaService,
            CpuAdaptationService cpuService,
            TagAdaptationService tagService,
            String firstStrategy,
            String secondStrategy) {
        return MockMvcBuilders.standaloneSetup(new CallbackController(
                new MetricCollectionService(), replicaService, cpuService, tagService, 1, 5, 750,
                firstStrategy, secondStrategy)).build();
    }

    private static String evaluationPayload(String currentValue) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String metric = json.writeValueAsString(Map.of("current_value", currentValue, "target_value", "1000"));
        return json.writeValueAsString(Map.of(
                "resource", Map.of("spec", Map.of("replicas", 2)),
                "metrics", List.of(Map.of("value", metric))));
    }
}
