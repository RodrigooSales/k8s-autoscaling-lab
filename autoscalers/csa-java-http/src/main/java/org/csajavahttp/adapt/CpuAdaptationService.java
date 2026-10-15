package org.csajavahttp.adapt;

import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Pod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public final class CpuAdaptationService {
    private final AdaptationStateService state;

    public CpuAdaptationService(AdaptationStateService state) {
        this.state = state;
    }

    public CpuState cpuState() {
        V1Deployment deployment = state.deployment();
        List<V1Pod> pods = state.pods(deployment);
        Integer current = state.currentMcpu(pods);
        if (current == null || current == 0) {
            throw new AdaptationException("Current CPU limit not found or unparsable", null);
        }
        return new CpuState(current, state.initialMcpu(deployment));
    }

    public Map<String, Object> adaptCpu(double multiplier, int maximum) {
        if (!Double.isFinite(multiplier)) {
            throw new IllegalArgumentException("cpu_multiplier must be finite");
        }
        V1Deployment deployment = state.deployment();
        if (state.rolloutInProgress(deployment)) {
            return Map.of("result", "skip");
        }
        int initial = state.initialMcpu(deployment);
        List<V1Pod> pods = state.pods(deployment);
        if (pods.isEmpty()) {
            throw new AdaptationException(
                    "Could not find Pods for Deployment " + deployment.getMetadata().getNamespace()
                            + "/" + deployment.getMetadata().getName(),
                    null);
        }
        Integer current = state.currentMcpu(pods);
        if (current == null || current == 0) {
            throw new AdaptationException("Current CPU limit not found or unparsable", null);
        }
        int adjusted;
        try {
            adjusted = BigDecimal.valueOf(current)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .intValueExact();
        } catch (ArithmeticException error) {
            throw new AdaptationException("Scaled CPU is outside the supported range", error);
        }
        adjusted = Math.max(initial, Math.min(adjusted, maximum));
        for (V1Pod pod : pods) {
            state.resize(pod, adjusted);
        }
        return Map.of("cpu", adjusted);
    }

    public record CpuState(int currentMcpu, int initialMcpu) {
    }
}
