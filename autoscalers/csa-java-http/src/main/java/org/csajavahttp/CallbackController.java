package org.csajavahttp;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.csajavahttp.adapt.AdaptationException;
import org.csajavahttp.adapt.CpuAdaptationService;
import org.csajavahttp.adapt.ReplicaAdaptationService;
import org.csajavahttp.adapt.TagAdaptationService;
import org.csajavahttp.evaluate.EvaluationService;
import org.csajavahttp.metric.MetricCollectionException;
import org.csajavahttp.metric.MetricCollectionService;
import org.csajavahttp.metric.MetricCollectionService.MetricSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CallbackController {
    private final MetricCollectionService metrics;
    private final EvaluationService evaluation = new EvaluationService();
    private final ReplicaAdaptationService replicas;
    private final CpuAdaptationService cpu;
    private final TagAdaptationService tag;
    private final int minReplicas;
    private final int maxReplicas;
    private final int maxCpu;
    private final Set<String> enabledStrategies;

    public CallbackController(
            MetricCollectionService metrics,
            ReplicaAdaptationService replicas,
            CpuAdaptationService cpu,
            TagAdaptationService tag,
            @Value("${minReplicas:1}") int minReplicas,
            @Value("${maxReplicas:10}") int maxReplicas,
            @Value("${maxCPU:1000}") int maxCpu,
            @Value("${enabled_strategies[0]:adapt_replicas}") String firstStrategy,
            @Value("${enabled_strategies[1]:}") String secondStrategy) {
        this.metrics = metrics;
        this.replicas = replicas;
        this.cpu = cpu;
        this.tag = tag;
        this.minReplicas = minReplicas;
        this.maxReplicas = maxReplicas;
        this.maxCpu = maxCpu;
        this.enabledStrategies = new HashSet<>();
        if (firstStrategy != null && !firstStrategy.isBlank()) {
            this.enabledStrategies.add(firstStrategy);
        }
        if (secondStrategy != null && !secondStrategy.isBlank()) {
            this.enabledStrategies.add(secondStrategy);
        }
    }

    @PostMapping("/metric")
    public Map<String, Object> metric(@RequestBody JsonNode spec) {
        MetricSnapshot snapshot = metrics.collect(spec);
        return Map.of(
                "current_replicas", snapshot.currentReplicas(),
                "target_value", snapshot.rawTargetMetricValue(),
                "current_value", snapshot.rawCurrentMetricValue());
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody JsonNode spec) {
        MetricSnapshot snapshot = metrics.snapshotFromEvaluation(spec);
        int currentMcpu = 0;
        int initialMcpu = 0;
        if (enabledStrategies.contains("adapt_cpu")) {
            CpuAdaptationService.CpuState state = cpu.cpuState();
            currentMcpu = state.currentMcpu();
            initialMcpu = state.initialMcpu();
        }
        EvaluationService.Decision decision = evaluation.evaluate(new EvaluationService.Input(
                snapshot.currentReplicas(),
                snapshot.currentMetricValue(),
                snapshot.targetMetricValue(),
                currentMcpu,
                initialMcpu,
                minReplicas,
                maxReplicas,
                maxCpu,
                enabledStrategies));
        if (decision == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("strategy", decision.strategy(), "parameters", decision.parameters()));
    }

    @PostMapping("/adapt/replicas")
    public Map<String, Integer> adaptReplicas(@RequestBody JsonNode spec) {
        JsonNode value = spec.path("evaluation").path("parameters").path("replicas");
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw new IllegalArgumentException("evaluation.parameters.replicas must be a non-negative integer");
        }
        int desiredReplicas = value.asInt();
        replicas.adaptReplicas(desiredReplicas);
        return Map.of("replicas", desiredReplicas);
    }

    @PostMapping("/adapt/cpu")
    public Map<String, Object> adaptCpu(@RequestBody JsonNode spec) {
        JsonNode value = spec.path("evaluation").path("parameters").path("cpu_multiplier");
        if (!value.isNumber()) {
            throw new IllegalArgumentException("evaluation.parameters.cpu_multiplier must be a number");
        }
        return cpu.adaptCpu(value.doubleValue(), maxCpu);
    }

    @PostMapping("/adapt/tag")
    public Map<String, Object> adaptTag(@RequestBody JsonNode spec) {
        JsonNode parameters = spec.path("evaluation").path("parameters");
        JsonNode tagUp = parameters.path("tag_up");
        JsonNode updateCpu = parameters.path("update_cpu");
        if (!tagUp.isBoolean() || (!updateCpu.isMissingNode() && !updateCpu.isBoolean())) {
            throw new IllegalArgumentException("tag_up and update_cpu must be booleans");
        }
        return tag.adaptTag(tagUp.booleanValue(), updateCpu.asBoolean(false));
    }

    @ExceptionHandler({MetricCollectionException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidCallback(Exception error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(AdaptationException.class)
    public ResponseEntity<Map<String, String>> adaptationFailure(AdaptationException error) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", error.getMessage()));
    }
}
