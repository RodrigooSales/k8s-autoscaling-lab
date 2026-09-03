package org.csajava;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class MetricProcessParityTest {
    private static final String VALID_INPUT = """
            {
              "resource": {"spec": {"replicas": 2}},
              "kubernetesMetrics": [{
                "spec": {"external": {"target": {"value": "1000"}}},
                "external": {"current": {"value": "1300000"}}
              }]
            }
            """;

    @Test
    public void matchesPythonStdoutForValidMetric() throws Exception {
        ProcessResult python = runPython(VALID_INPUT);
        ProcessResult java = runJava(VALID_INPUT);

        assertEquals(0, python.exitCode());
        assertEquals(python, java);
    }

    @Test
    public void matchesPythonFailureForMissingMetric() throws Exception {
        String input = "{\"resource\":{\"spec\":{\"replicas\":2}}}";

        ProcessResult python = runPython(input);
        ProcessResult java = runJava(input);

        assertEquals(1, python.exitCode());
        assertEquals("", python.stdout());
        assertEquals(python.exitCode(), java.exitCode());
        assertEquals(python.stdout(), java.stdout());
    }

    @Test
    public void matchesPythonInvalidPayloadMatrix() throws Exception {
        List<String> inputs = List.of(
                "",
                "{}",
                "{\"resource\":{\"spec\":{}},\"kubernetesMetrics\":[]}",
                "{\"resource\":{\"spec\":{\"replicas\":2}},\"kubernetesMetrics\":[]}",
                "{\"resource\":{\"spec\":{\"replicas\":2}},\"kubernetesMetrics\":[{}]}",
                """
                {
                  "resource": {"spec": {"replicas": 2}},
                  "kubernetesMetrics": [{
                    "spec": {"external": {"target": {}}},
                    "external": {"current": {"value": "1300000"}}
                  }]
                }
                """,
                """
                {
                  "resource": {"spec": {"replicas": 2}},
                  "kubernetesMetrics": [{
                    "spec": {"external": {"target": {"value": "1000"}}},
                    "external": {"current": {}}
                  }]
                }
                """);

        for (String input : inputs) {
            ProcessResult python = runPython(input);
            ProcessResult java = runJava(input);
            assertEquals(input, python.exitCode(), java.exitCode());
            assertEquals(input, python.stdout(), java.stdout());
        }
    }

    @Test
    public void matchesPythonStdoutForNullValues() throws Exception {
        String input = """
                {
                  "resource": {"spec": {"replicas": null}},
                  "kubernetesMetrics": [{
                    "spec": {"external": {"target": {"value": null}}},
                    "external": {"current": {"value": null}}
                  }]
                }
                """;

        assertEquals(runPython(input), runJava(input));
    }

    @Test
    public void matchesPythonStringEscaping() throws Exception {
        String input = """
                {
                  "resource": {"spec": {"replicas": 2}},
                  "kubernetesMetrics": [{
                    "spec": {"external": {"target": {"value": "<latência>"}}},
                    "external": {"current": {"value": "linha\\nseguinte"}}
                  }]
                }
                """;

        assertEquals(runPython(input), runJava(input));
    }

    @Test
    public void matchesPythonPrimitiveTypes() throws Exception {
        String input = """
                {
                  "resource": {"spec": {"replicas": "2"}},
                  "kubernetesMetrics": [{
                    "spec": {"external": {"target": {"value": 1000}}},
                    "external": {"current": {"value": true}}
                  }]
                }
                """;

        assertEquals(runPython(input), runJava(input));
    }

    private static ProcessResult runPython(String input) throws Exception {
        Process process = new ProcessBuilder("python", "../csa/scripts/metric_nginx_req_duration.py").start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.getErrorStream().readAllBytes();
        return new ProcessResult(process.waitFor(), stdout);
    }

    private static ProcessResult runJava(String input) throws Exception {
        Path config = Files.createTempFile("csa-java-metric", ".yaml");
        Files.writeString(config, "{}\n");

        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            System.setOut(out);
            System.setErr(err);
            Method run = App.class.getDeclaredMethod("run", String[].class, String.class);
            run.setAccessible(true);
            int exitCode = (int) run.invoke(null, new String[] {"-m", "metric"}, config.toString());
            return new ProcessResult(exitCode, stdout.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record ProcessResult(int exitCode, String stdout) {
    }
}
