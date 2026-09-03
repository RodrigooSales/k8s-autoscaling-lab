package org.csajava.runtime.evaluate;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.google.gson.reflect.TypeToken;
import org.csajava.context.RuntimeContext;
import org.csajava.io.JsonOut;
import org.junit.Test;

public class EvaluateDifferentialTest {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    @Test
    public void matchesPythonDecisionMatrix() throws Exception {
        List<Case> cases = List.of(
                testCase("absent strategy", "960000", "1000", Map.of(), state(500, 500)),
                testCase("empty strategies", "960000", "1000", Map.of("enabled_strategies", List.of()), state(500, 500)),
                testCase("invalid strategy item", "960000", "1000", Map.of("enabled_strategies", List.of(1)), state(500, 500)),
                testCase("string strategy", "960000", "1000", Map.of(
                        "enabled_strategies", "adapt_cpu", "maxReplicas", 1, "maxCPU", 1000), state(500, 500)),
                testCase("first baseline", "800000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "minReplicas", 1),
                        Map.of("current_mcpu", 500, "spec_mcpu", 500)),
                testCase("missing pods", "960000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1),
                        Map.of("initial_mcpu", 500)),
                testCase("zero CPU", "960000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1),
                        Map.of("current_mcpu", 0, "initial_mcpu", 500)),
                testCase("missing deployment", "960000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1),
                        Map.of("current_mcpu", 500, "initial_mcpu", 500, "deployment_missing", true)),
                testCase("below low boundary", "899999", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "minReplicas", 1), state(600, 500)),
                testCase("low boundary", "900000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu")), state(600, 500)),
                testCase("deadband", "920000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu")), state(500, 500)),
                testCase("below high boundary", "949999", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu")), state(500, 500)),
                testCase("high boundary", "950000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1), state(500, 500)),
                testCase("above target", "1300000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1), state(500, 500)),
                testCase("CPU max tag fallback", "1300000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu", "adapt_tag"),
                        "maxReplicas", 1, "maxCPU", 750), state(750, 500)),
                testCase("replica minimum", "800000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_replicas"), "minReplicas", 1), state(500, 500)),
                testCase("replica maximum", "1300000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_replicas"), "maxReplicas", 1), state(500, 500)),
                testCase("quantity suffix", "950T", "1T", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1), state(500, 500)),
                testCase("invalid quantity", "invalid", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1), state(500, 500)),
                testCase("full precision", "9500000000001", "10000000000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1), state(500, 500)),
                testCase("Kubernetes failure", "960000", "1000", Map.of(
                        "enabled_strategies", List.of("adapt_cpu"), "maxReplicas", 1),
                        Map.of("current_mcpu", 500, "initial_mcpu", 500, "kubernetes_error", true)));

        for (Case testCase : cases) {
            assertEquals(testCase.name(), runPython(testCase), runJava(testCase));
        }
    }

    @Test
    public void matchesPythonInvalidInputMatrix() throws Exception {
        List<Case> cases = List.of(
                rawCase("missing metadata", """
                        {"resource":{"spec":{"replicas":1}},"metrics":[]}
                        """),
                rawCase("missing replicas", """
                        {"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{}},"metrics":[]}
                        """),
                rawCase("empty metrics", """
                        {"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[]}
                        """),
                rawCase("malformed metric JSON", """
                        {"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{"}]}
                        """),
                rawCase("missing metric values", """
                        {"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{}"}]}
                        """),
                rawCase("zero target", """
                        {"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{\\"current_value\\":\\"1\\",\\"target_value\\":\\"0\\"}"}]}
                        """));

        for (Case testCase : cases) {
            assertEquals(testCase.name(), runPython(testCase), runJava(testCase));
        }
    }

    private static ProcessResult runPython(Case testCase) throws Exception {
        Path oracle = Path.of(EvaluateDifferentialTest.class
                .getResource("/python_evaluate_oracle.py").toURI());
        ProcessBuilder builder = new ProcessBuilder("python", oracle.toString());
        builder.environment().put("CSA_PYTHON_SCRIPTS", Path.of("..", "csa", "scripts").toAbsolutePath().normalize().toString());
        Process process = builder.start();
        process.getOutputStream().write(GSON.toJson(testCase.payload()).getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.getErrorStream().readAllBytes();
        return new ProcessResult(process.waitFor(), stdout);
    }

    private static ProcessResult runJava(Case testCase) {
        JsonObject stdin = testCase.payload().getAsJsonObject("stdin");
        Map<String, Object> config = GSON.fromJson(testCase.payload().get("config"), MAP_TYPE);
        Map<String, Object> state = GSON.fromJson(testCase.payload().get("state"), MAP_TYPE);
        RuntimeContext context = new RuntimeContext(GSON.toJson(stdin), stdin, config, state);

        try {
            Object result = EvaluateRuntime.evaluate(context);
            if (result == null) {
                return new ProcessResult(0, "");
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            PrintStream original = System.out;
            try (PrintStream replacement = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
                System.setOut(replacement);
                JsonOut.write(result);
            } finally {
                System.setOut(original);
            }
            return new ProcessResult(0, stdout.toString(StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            return new ProcessResult(1, "");
        }
    }

    private static Case testCase(
            String name,
            String currentValue,
            String targetValue,
            Map<String, Object> config,
            Map<String, Object> state) {
        JsonObject stdin = JsonParser.parseString("""
                {
                  "resource": {
                    "metadata": {"name": "kube-znn", "namespace": "default"},
                    "spec": {"replicas": 1}
                  },
                  "metrics": [{"value": "{}"}]
                }
                """).getAsJsonObject();
        JsonObject metric = new JsonObject();
        metric.addProperty("current_value", currentValue);
        metric.addProperty("target_value", targetValue);
        stdin.getAsJsonArray("metrics").get(0).getAsJsonObject().addProperty("value", GSON.toJson(metric));

        JsonObject payload = new JsonObject();
        payload.add("stdin", stdin);
        payload.add("config", GSON.toJsonTree(config));
        payload.add("state", GSON.toJsonTree(state));
        return new Case(name, payload);
    }

    private static Case rawCase(String name, String stdin) {
        JsonObject payload = new JsonObject();
        payload.add("stdin", JsonParser.parseString(stdin).getAsJsonObject());
        payload.add("config", GSON.toJsonTree(Map.of("enabled_strategies", List.of("adapt_cpu"))));
        payload.add("state", GSON.toJsonTree(state(500, 500)));
        return new Case(name, payload);
    }

    private static Map<String, Object> state(int currentMcpu, int initialMcpu) {
        return Map.of("current_mcpu", currentMcpu, "initial_mcpu", initialMcpu);
    }

    private record Case(String name, JsonObject payload) {
    }

    private record ProcessResult(int exitCode, String stdout) {
    }
}
