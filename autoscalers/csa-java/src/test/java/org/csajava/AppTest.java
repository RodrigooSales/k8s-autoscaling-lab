package org.csajava;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class AppTest {
    @Test
    public void validMetricExitsZeroAndWritesPythonJson() {
        ProcessResult result = run("""
                {
                  "resource":{"spec":{"replicas":2}},
                  "kubernetesMetrics":[{
                    "spec":{"external":{"target":{"value":"1000"}}},
                    "external":{"current":{"value":"1300000"}}
                  }]
                }
                """);

        assertEquals(0, result.exitCode());
        assertEquals(
                "{\"current_replicas\": 2, \"target_value\": \"1000\", \"current_value\": \"1300000\"}",
                result.stdout());
        assertEquals("metric       -   INFO - Starting metric script\n", normalizeLog(result.stderr()));
        assertEquals(normalizeLog(result.stderr()), normalizeLog(result.log()));
    }

    @Test
    public void metricFailureHasNoJsonAndExitsNonZero() {
        ProcessResult result = run("{}");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("IllegalArgumentException"));
    }

    @Test
    public void malformedJsonHasNoJsonAndExitsNonZero() {
        ProcessResult result = run("{");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("JsonSyntaxException"));
    }

    @Test
    public void malformedJsonFollowsEachPythonProcessContract() {
        for (String mode : new String[] {"adapt_replicas", "adapt_cpu", "adapt_tag"}) {
            ProcessResult result = run(mode, "{");

            assertEquals(mode, 0, result.exitCode());
            assertEquals(mode, "", result.stdout());
            assertTrue(mode, normalizeLog(result.stderr()).startsWith(loggerName(mode) + " -  ERROR - Invalid JSON on stdin: "));
            assertEquals(normalizeLog(result.stderr()), normalizeLog(result.log()));
        }

        ProcessResult evaluate = run("evaluate", "{");
        assertEquals(1, evaluate.exitCode());
        assertEquals("", evaluate.stdout());
        assertTrue(normalizeLog(evaluate.stderr()).startsWith("""
                eval_main    -   INFO - evaluate main
                evaluate     -  ERROR - Invalid JSON on stdin: """));
        assertEquals(2, normalizeLog(evaluate.log()).lines().count());
        assertTrue(evaluate.stderr().contains("JsonSyntaxException"));
    }

    @Test
    public void metricDoesNotLoadTheAdaptationConfig() throws Exception {
        Path invalidConfig = Files.createTempFile("csa-java-invalid", ".yaml");
        Files.writeString(invalidConfig, "[");

        ProcessResult result = run("metric", """
                {
                  "resource":{"spec":{"replicas":2}},
                  "kubernetesMetrics":[{
                    "spec":{"external":{"target":{"value":"1000"}}},
                    "external":{"current":{"value":"1300000"}}
                  }]
                }
                """, invalidConfig.toString());

        assertEquals(0, result.exitCode());
    }

    private static ProcessResult run(String stdin) {
        return run("metric", stdin);
    }

    private static ProcessResult run(String mode, String stdin) {
        return run(mode, stdin, "config.yaml");
    }

    private static ProcessResult run(String mode, String stdin, String configPath) {
        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            Path log = Files.createTempFile("csa-java-app", ".log");
            System.setProperty("csa.adapter.log", log.toString());
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = App.run(new String[] {"-m", mode}, configPath);
            return new ProcessResult(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    Files.readString(log));
        } catch (Exception error) {
            throw new AssertionError(error);
        } finally {
            System.clearProperty("csa.adapter.log");
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String normalizeLog(String value) {
        return value.replaceAll("(?m)^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} ", "");
    }

    private static String loggerName(String mode) {
        return switch (mode) {
            case "adapt_replicas" -> "adapt_repl  ";
            case "adapt_cpu" -> "adapt_cpu   ";
            case "adapt_tag" -> "adapt_tag   ";
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private record ProcessResult(int exitCode, String stdout, String stderr, String log) {
    }
}
