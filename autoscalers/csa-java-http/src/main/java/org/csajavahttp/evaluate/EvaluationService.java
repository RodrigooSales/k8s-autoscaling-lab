package org.csajavahttp.evaluate;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

public final class EvaluationService {
    private static final String STRATEGY_REPLICAS = "adapt_replicas";
    private static final String STRATEGY_CPU = "adapt_cpu";
    private static final String STRATEGY_TAG = "adapt_tag";

    public Decision evaluate(Input input) {
        BigInteger scaledTarget = input.targetMetricValue().multiply(BigInteger.valueOf(1000));
        if (scaledTarget.signum() == 0) {
            throw new IllegalArgumentException("target metric is zero");
        }

        double rate = input.currentMetricValue().doubleValue() / scaledTarget.doubleValue();
        int desiredReplicas = (int) Math.ceil(input.currentReplicas() * rate);
        Set<String> strategies = input.enabledStrategies();

        if (rate >= 0.95d) {
            if (strategies.contains(STRATEGY_REPLICAS) && input.currentReplicas() < input.maxReplicas()) {
                return new Decision(
                        STRATEGY_REPLICAS,
                        Map.of("replicas", Math.min(desiredReplicas, input.maxReplicas())));
            }
            if (strategies.contains(STRATEGY_CPU) && input.currentMcpu() < input.maxCpu()) {
                return new Decision(STRATEGY_CPU, Map.of("cpu_multiplier", rate));
            }
            if (strategies.contains(STRATEGY_TAG)) {
                return new Decision(
                        STRATEGY_TAG,
                        Map.of("tag_up", false, "update_cpu", strategies.contains(STRATEGY_CPU)));
            }
        }

        if (rate < 0.90d) {
            if (strategies.contains(STRATEGY_REPLICAS) && input.currentReplicas() > input.minReplicas()) {
                return new Decision(
                        STRATEGY_REPLICAS,
                        Map.of("replicas", Math.max(desiredReplicas, input.minReplicas())));
            }
            if (strategies.contains(STRATEGY_CPU) && input.currentMcpu() > input.initialMcpu()) {
                return new Decision(STRATEGY_CPU, Map.of("cpu_multiplier", rate));
            }
            if (strategies.contains(STRATEGY_TAG)) {
                return new Decision(STRATEGY_TAG, Map.of("tag_up", true));
            }
        }

        return null;
    }

    public record Input(
            int currentReplicas,
            BigInteger currentMetricValue,
            BigInteger targetMetricValue,
            int currentMcpu,
            int initialMcpu,
            int minReplicas,
            int maxReplicas,
            int maxCpu,
            Set<String> enabledStrategies) {
    }

    public record Decision(String strategy, Map<String, Object> parameters) {
    }
}
