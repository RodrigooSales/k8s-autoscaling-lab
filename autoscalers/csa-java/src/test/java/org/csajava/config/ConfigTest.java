package org.csajava.config;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class ConfigTest {
    @Test
    public void usesTheExperimentalConfiguration() throws Exception {
        Map<String, Object> config = load(Path.of("config.yaml"));

        assertEquals(2000, timeout(config, "metric"));
        assertEquals(8000, timeout(config, "evaluate"));
        assertEquals(8000, adaptTimeout(config, "adapt_replicas"));
        assertEquals(10000, adaptTimeout(config, "adapt_cpu"));
        assertEquals(10000, adaptTimeout(config, "adapt_tag"));
        assertEquals(5000, config.get("interval"));
        assertEquals(1, config.get("minReplicas"));
        assertEquals(5, config.get("maxReplicas"));
        assertEquals(750, config.get("maxCPU"));
        assertEquals(List.of("adapt_cpu", "adapt_tag"), config.get("enabled_strategies"));
        assertEquals(true, config.get("requireKubernetesMetrics"));
        assertEquals(4, config.get("logVerbosity"));
    }

    @Test
    public void versionsEachAdaptationProfile() throws Exception {
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
