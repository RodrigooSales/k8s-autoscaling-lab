package org.csajava.runtime;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.csajava.runtime.adapt.cpu.AdaptCpuRuntime;
import org.csajava.runtime.adapt.replicas.AdaptReplicasRuntime;
import org.csajava.runtime.adapt.tag.AdaptTagRuntime;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.evaluate.EvaluateRuntime;
import org.csajava.runtime.metric.MetricRuntime;

public class ContractParityTest {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    @Test
    public void metricBasicContract() throws IOException {
        JsonObject fixture = readFixture("metric.basic");
        RuntimeContext context = contextFromFixture(fixture);

        JsonElement actual = asJson(MetricRuntime.evaluate(context));
        JsonElement expected = fixture.get("stdout");

        assertEquals(expected, actual);
    }

    @Test
    public void evaluateHighLoadCpuContract() throws IOException {
        assertEvaluateCase("evaluate.high_load_cpu");
    }

    @Test
    public void evaluateHighLoadTagFallbackContract() throws IOException {
        assertEvaluateCase("evaluate.high_load_tag_fallback");
    }

    @Test
    public void evaluateLowLoadCpuContract() throws IOException {
        assertEvaluateCase("evaluate.low_load_cpu");
    }

    @Test
    public void evaluateLowLoadTagFallbackContract() throws IOException {
        assertEvaluateCase("evaluate.low_load_tag_fallback");
    }

    @Test
    public void adaptReplicasSuccessContract() throws IOException {
        JsonObject fixture = readFixture("adapt_replicas.success");
        RuntimeContext context = contextFromFixture(fixture);

        JsonElement actual = asJson(AdaptReplicasRuntime.evaluate(context));
        JsonElement expected = fixture.get("stdout");

        assertEquals(expected, actual);
    }

    @Test
    public void adaptCpuSuccessContract() throws IOException {
        assertAdaptCpuCase("adapt_cpu.success");
    }

    @Test
    public void adaptCpuSkipRolloutContract() throws IOException {
        assertAdaptCpuCase("adapt_cpu.skip_rollout");
    }

    @Test
    public void adaptCpuErrorInvalidParameterContract() throws IOException {
        assertAdaptCpuCase("adapt_cpu.error_invalid_parameter");
    }

    @Test
    public void adaptTagSuccessDownContract() throws IOException {
        assertAdaptTagCase("adapt_tag.success_down");
    }

    @Test
    public void adaptTagSkipRolloutContract() throws IOException {
        assertAdaptTagCase("adapt_tag.skip_rollout");
    }

    @Test
    public void adaptTagSkipBoundaryContract() throws IOException {
        assertAdaptTagCase("adapt_tag.skip_boundary");
    }

    private void assertEvaluateCase(String caseId) throws IOException {
        JsonObject fixture = readFixture(caseId);
        RuntimeContext context = contextFromFixture(fixture);

        JsonElement actual = asJson(EvaluateRuntime.evaluate(context));
        JsonElement expected = fixture.get("stdout");

        assertEquals(expected, actual);
    }

    private void assertAdaptCpuCase(String caseId) throws IOException {
        JsonObject fixture = readFixture(caseId);
        RuntimeContext context = contextFromFixture(fixture);

        JsonElement actual = asJson(AdaptCpuRuntime.evaluate(context));
        JsonElement expected = fixture.get("stdout");

        assertEquals(expected, actual);
    }

    private void assertAdaptTagCase(String caseId) throws IOException {
        JsonObject fixture = readFixture(caseId);
        RuntimeContext context = contextFromFixture(fixture);

        JsonElement actual = asJson(AdaptTagRuntime.evaluate(context));
        JsonElement expected = fixture.get("stdout");

        assertEquals(expected, actual);
    }

    private static RuntimeContext contextFromFixture(JsonObject fixture) {
        JsonObject stdin = fixture.getAsJsonObject("stdin");
        String stdinRaw = GSON.toJson(stdin);

        Map<String, Object> config = Collections.emptyMap();
        Map<String, Object> hints = Collections.emptyMap();

        JsonObject implicitInput = fixture.has("implicitInput") && fixture.get("implicitInput").isJsonObject()
                ? fixture.getAsJsonObject("implicitInput")
                : null;

        if (implicitInput != null && implicitInput.has("config") && implicitInput.get("config").isJsonObject()) {
            config = GSON.fromJson(implicitInput.get("config"), MAP_TYPE);
        }

        if (implicitInput != null && implicitInput.has("kubernetesState")
                && implicitInput.get("kubernetesState").isJsonObject()) {
            hints = GSON.fromJson(implicitInput.get("kubernetesState"), MAP_TYPE);
        }

        return new RuntimeContext(stdinRaw, stdin, config, hints);
    }

    private static JsonObject readFixture(String id) throws IOException {
        Path path = Path.of("contracts", "cases", id + ".json");
        String content = Files.readString(path);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    private static JsonElement asJson(Object value) {
        return GSON.toJsonTree(value);
    }
}
