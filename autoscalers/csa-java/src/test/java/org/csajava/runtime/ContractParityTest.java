package org.csajava.runtime;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.csajava.cli.Mode;
import org.csajava.context.RuntimeContext;
import org.junit.Test;
import org.csajava.runtime.adapt.cpu.AdaptCpuRuntime;
import org.csajava.runtime.adapt.replicas.AdaptReplicasRuntime;
import org.csajava.runtime.adapt.tag.AdaptTagRuntime;
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
    public void evaluateNoAdaptationProducesNoStdout() throws IOException {
        RuntimeContext context = contextFromFixture(readNoAdaptationFixture());

        assertEquals(null, EvaluateRuntime.evaluate(context));
        assertEquals("", stdoutFromEvaluateHandler(context));
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

    private static JsonObject readNoAdaptationFixture() {
        return JsonParser.parseString("""
                {
                  "stdin": {
                    "resource": {
                      "metadata": {
                        "name": "kube-znn",
                        "namespace": "default"
                      },
                      "spec": {
                        "replicas": 2
                      }
                    },
                    "metrics": [
                      {
                        "name": "metric_nginx_req_duration",
                        "value": "{\\"current_replicas\\":2,\\"target_value\\":\\"1000\\",\\"current_value\\":\\"920000\\"}"
                      }
                    ]
                  },
                  "implicitInput": {
                    "config": {
                      "enabled_strategies": [
                        "adapt_cpu",
                        "adapt_tag"
                      ],
                      "minReplicas": 1,
                      "maxReplicas": 5,
                      "maxCPU": 1000
                    },
                    "kubernetesState": {
                      "current_mcpu": 500,
                      "initial_mcpu": 500
                    }
                  }
                }
                """).getAsJsonObject();
    }

    private static String stdoutFromEvaluateHandler(RuntimeContext context) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream replacement = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            ModeHandlers.forMode(Mode.EVALUATE).handle(context);
        } finally {
            System.setOut(originalOut);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
