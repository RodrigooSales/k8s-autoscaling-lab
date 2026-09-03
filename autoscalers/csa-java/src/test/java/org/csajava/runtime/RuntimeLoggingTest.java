package org.csajava.runtime;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.csajava.runtime.adapt.cpu.AdaptCpuRuntime;
import org.csajava.runtime.adapt.replicas.AdaptReplicasRuntime;
import org.csajava.runtime.adapt.tag.AdaptTagRuntime;
import org.csajava.runtime.evaluate.EvaluateRuntime;
import org.junit.Test;

public class RuntimeLoggingTest {
    @Test
    public void logsSuccessfulExperimentalPathsLikePython() throws Exception {
        assertLogs(
                () -> EvaluateRuntime.evaluate(context(
                        """
                        {
                          "resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":2}},
                          "metrics":[{"value":"{\\"target_value\\":\\"1000\\",\\"current_value\\":\\"1300000\\"}"}]
                        }
                        """,
                        Map.of(
                                "enabled_strategies", List.of("adapt_cpu", "adapt_tag"),
                                "minReplicas", 1,
                                "maxReplicas", 5,
                                "maxCPU", 1000),
                        Map.of("current_mcpu", 500, "initial_mcpu", 500))),
                """
                eval_main    -   INFO - evaluate main
                evaluate     -   INFO - Starting evaluate script
                evaluate     -   INFO -   loaded config
                evaluate     -   INFO -   loaded spec data
                evaluate     -   INFO -   parsed config data
                evaluate     -   INFO - rate 1300000 / 1000000 = 1.3
                evaluate     -   INFO - plan for rate 1.3; stragegies ['adapt_cpu', 'adapt_tag']
                evaluate     -   INFO - {"strategy": "adapt_cpu", "parameters": {"cpu_multiplier": 1.3}}
                """);

        assertLogs(
                () -> AdaptReplicasRuntime.evaluate(context(
                        """
                        {"evaluation":{"parameters":{"replicas":3}}}
                        """,
                        Map.of(),
                        Map.of("deployment_patch_success", true))),
                """
                adapt_repl   -   INFO - Starting adapt_replicas script
                adapt_repl   -   INFO - Scaling to 3 replicas
                """);

        assertLogs(
                () -> AdaptCpuRuntime.evaluate(context(
                        """
                        {"evaluation":{"parameters":{"cpu_multiplier":1.3}}}
                        """,
                        Map.of("maxCPU", 1000),
                        Map.of(
                                "stored_initial_mcpu", 500,
                                "current_mcpu", 500,
                                "pods", List.of("pod-a"),
                                "pod_resize_success", true))),
                """
                adapt_cpu    -   INFO - adapt_cpu for rate 1.3
                adapt_cpu    -   INFO - Read initial_mcpu_data 500.
                adapt_cpu    -   INFO - Calculated new_mcpu 650
                adapt_cpu    -   INFO - Scaled cpu to 650
                """);

        assertLogs(
                () -> AdaptTagRuntime.evaluate(context(
                        """
                        {"evaluation":{"parameters":{"tag_up":false,"update_cpu":true}}}
                        """,
                        Map.of(),
                        Map.of(
                                "container_znn_image", "registry.k8s.lab/kube-znn:600k",
                                "stored_initial_tag", "600k",
                                "stored_initial_mcpu", 500,
                                "current_mcpu", 700,
                                "deployment_patch_success", true))),
                """
                adapt_tag    -   INFO - Adapting tag to 400k
                """);
    }

    private static RuntimeContext context(String input, Map<String, Object> config, Map<String, Object> hints) {
        JsonObject json = JsonParser.parseString(input).getAsJsonObject();
        return new RuntimeContext(input, json, config, hints);
    }

    private static void assertLogs(Runnable action, String expected) throws Exception {
        PrintStream originalErr = System.err;
        String originalPath = System.getProperty("csa.adapter.log");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Path log = Files.createTempFile("csa-java-runtime", ".log");
        try {
            System.setProperty("csa.adapter.log", log.toString());
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

            action.run();

            assertEquals(expected, normalize(stderr.toString(StandardCharsets.UTF_8)));
            assertEquals(expected, normalize(Files.readString(log)));
        } finally {
            if (originalPath == null) {
                System.clearProperty("csa.adapter.log");
            } else {
                System.setProperty("csa.adapter.log", originalPath);
            }
            System.setErr(originalErr);
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} ", "");
    }
}
