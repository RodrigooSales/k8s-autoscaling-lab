package org.csajava.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.csajava.util.YamlMap;

public class RuntimeContextTest {
    @Test
    public void parsesJsonStdinAndLoadsConfig() throws Exception {
        Path config = Files.createTempFile("csa-java-config", ".yaml");
        Files.writeString(config, "interval: 5000\nmaxCPU: 1000\n");

        RuntimeContext context = RuntimeContext.load(config.toString(), "{\"a\":1}");

        assertNotNull(context.stdinJson());
        assertEquals(1, context.stdinJson().get("a").getAsInt());
        assertEquals(5000, YamlMap.integer(context.config(), "interval").intValue());
        assertTrue(context.hints().isEmpty());
    }

    @Test
    public void returnsEmptyJsonOnBlankStdin() {
        RuntimeContext context = RuntimeContext.load("/file/that/does/not/exist.yaml", " ");

        assertTrue(context.stdinJson().keySet().isEmpty());
        assertTrue(context.hints().isEmpty());
    }
}
