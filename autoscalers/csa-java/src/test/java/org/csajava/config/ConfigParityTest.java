package org.csajava.config;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class ConfigParityTest {
    @Test
    public void matchesPythonBehavioralConfiguration() throws Exception {
        Map<String, Object> python = load(Path.of("..", "csa", "config.yaml"));
        Map<String, Object> java = load(Path.of("config.yaml"));

        assertEquals(timeout(python, "metric"), timeout(java, "metric"));
        assertEquals(timeout(python, "evaluate"), timeout(java, "evaluate"));
        for (String strategy : List.of("adapt_replicas", "adapt_cpu", "adapt_tag")) {
            assertEquals(adaptTimeout(python, strategy), adaptTimeout(java, strategy));
        }
        for (String key : List.of(
                "interval",
                "minReplicas",
                "maxReplicas",
                "maxCPU",
                "kubernetesMetricSpecs",
                "enabled_strategies",
                "requireKubernetesMetrics",
                "logVerbosity")) {
            assertEquals(key, python.get(key), java.get(key));
        }
    }

    @Test
    public void versionsTheRecoveredPythonProfiles() throws Exception {
        Map<String, List<String>> expected = Map.of(
                "h", List.of("adapt_replicas"),
                "hq", List.of("adapt_replicas", "adapt_tag"),
                "v", List.of("adapt_cpu"),
                "vq", List.of("adapt_cpu", "adapt_tag"));
        Map<String, Object> canonical = load(Path.of("config.yaml"));

        for (Map.Entry<String, List<String>> profile : expected.entrySet()) {
            Map<String, Object> actual = load(Path.of("profiles", profile.getKey() + ".yaml"));
            assertEquals(profile.getValue(), actual.get("enabled_strategies"));
            for (String key : List.of(
                    "metric",
                    "evaluate",
                    "adapt",
                    "interval",
                    "minReplicas",
                    "maxReplicas",
                    "maxCPU",
                    "kubernetesMetricSpecs",
                    "requireKubernetesMetrics",
                    "logVerbosity")) {
                assertEquals(profile.getKey() + ": " + key, canonical.get(key), actual.get(key));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object timeout(Map<String, Object> config, String phase) {
        return ((Map<String, Object>) config.get(phase)).get("timeout");
    }

    @SuppressWarnings("unchecked")
    private static Object adaptTimeout(Map<String, Object> config, String strategy) {
        Map<String, Object> adapt = (Map<String, Object>) config.get("adapt");
        return ((Map<String, Object>) adapt.get(strategy)).get("timeout");
    }
}
