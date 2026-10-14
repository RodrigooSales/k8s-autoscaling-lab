package org.csajavahttp;

import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.csajavahttp.adapt.ReplicaAdaptationService;
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
    private final int minReplicas;
    private final int maxReplicas;
    private final int maxCpu;
    private final Set<String> enabledStrategies;

    public CallbackController(
            MetricCollectionService metrics,
            ReplicaAdaptationService replicas,
            @Value("${minReplicas:1}") int minReplicas,
            @Value("${maxReplicas:10}") int maxReplicas,
            @Value("${maxCPU:1000}") int maxCpu,
            @Value("${enabled_strategies[0]:adapt_replicas}") String firstStrategy,
            @Value("${enabled_strategies[1]:}") String secondStrategy) {
        this.metrics = metrics;
        this.replicas = replicas;
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
        EvaluationService.Decision decision = evaluation.evaluate(new EvaluationService.Input(
                snapshot.currentReplicas(),
                snapshot.currentMetricValue(),
                snapshot.targetMetricValue(),
                0,
                0,
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

    @ExceptionHandler({MetricCollectionException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidCallback(Exception error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }
}
