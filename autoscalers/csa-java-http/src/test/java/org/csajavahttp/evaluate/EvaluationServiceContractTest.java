package org.csajavahttp.evaluate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluationServiceContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of("..", "contracts", "cases");

    @Test
    void matchesSharedEvaluationContracts() throws Exception {
        EvaluationService service = new EvaluationService();
        JsonNode index = JSON.readTree(CONTRACTS.resolve("index.json").toFile());
        int evaluatedCases = 0;

        for (JsonNode caseId : index.path("cases")) {
            JsonNode fixture = JSON.readTree(CONTRACTS.resolve(caseId.asText() + ".json").toFile());
            if (!"evaluate".equals(fixture.path("operation").asText())) {
                continue;
            }

            JsonNode input = fixture.path("input");
            JsonNode config = fixture.path("config");
            JsonNode state = fixture.path("state");
            JsonNode metric = JSON.readTree(input.path("metrics").get(0).path("value").asText());
            Set<String> enabledStrategies = new HashSet<>();
            config.path("enabled_strategies").forEach(strategy -> enabledStrategies.add(strategy.asText()));

            EvaluationService.Input evaluationInput = new EvaluationService.Input(
                    input.path("resource").path("spec").path("replicas").asInt(),
                    new BigInteger(metric.path("current_value").asText()),
                    new BigInteger(metric.path("target_value").asText()),
                    state.path("current_mcpu").asInt(),
                    state.path("initial_mcpu").asInt(),
                    config.path("minReplicas").asInt(),
                    config.path("maxReplicas").asInt(),
                    config.path("maxCPU").asInt(),
                    enabledStrategies);

            assertEquals(
                    fixture.path("expectedResult"),
                    JSON.valueToTree(service.evaluate(evaluationInput)),
                    caseId.asText());
            evaluatedCases++;
        }

        assertTrue(evaluatedCases > 0, "no evaluation contract fixtures found");
    }
}
