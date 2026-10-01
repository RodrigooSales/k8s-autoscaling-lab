package org.csajava.runtime.metric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.csajava.context.RuntimeContext;
import org.junit.Test;

public class MetricRuntimeTest {
    @Test
    public void preservesMetricJsonTypesFromTheFirstMetric() throws Exception {
        JsonObject stdin = JsonParser.parseString("""
                {
                  "resource":{"spec":{"replicas":3}},
                  "kubernetesMetrics":[
                    {
                      "spec":{"external":{"target":{"value":1000}}},
                      "external":{"current":{"value":950000}}
                    },
                    {
                      "spec":{"external":{"target":{"value":"ignored"}}},
                      "external":{"current":{"value":"ignored"}}
                    }
                  ]
                }
                """).getAsJsonObject();
        RuntimeContext context = new RuntimeContext(stdin.toString(), stdin, Map.of(), Map.of());
        String originalLogPath = System.getProperty("csa.adapter.log");
        Path logPath = Files.createTempFile("csa-java-metric", ".log");
        try {
            System.setProperty("csa.adapter.log", logPath.toString());

            JsonObject result = (JsonObject) MetricRuntime.evaluate(context);

            assertTrue(result.get("current_replicas").getAsJsonPrimitive().isNumber());
            assertTrue(result.get("target_value").getAsJsonPrimitive().isNumber());
            assertTrue(result.get("current_value").getAsJsonPrimitive().isNumber());
            assertEquals(3, result.get("current_replicas").getAsInt());
            assertEquals(1000, result.get("target_value").getAsInt());
            assertEquals(950000, result.get("current_value").getAsInt());
        } finally {
            if (originalLogPath == null) {
                System.clearProperty("csa.adapter.log");
            } else {
                System.setProperty("csa.adapter.log", originalLogPath);
            }
        }
    }
}
