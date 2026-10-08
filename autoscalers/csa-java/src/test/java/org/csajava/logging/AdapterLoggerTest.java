package org.csajava.logging;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;

public class AdapterLoggerTest {
    @Test
    public void writesPythonLogFormatToStderrAndFile() throws Exception {
        Path file = Files.createTempFile("csa-java-adapter", ".log");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T12:34:56.789Z"), ZoneOffset.UTC);
        AdapterLogger logger = new AdapterLogger(
                "metric", file, new PrintStream(stderr, true, StandardCharsets.UTF_8), clock);

        logger.info("Starting metric script");
        logger.error("failed");
        logger.fatal("fatal");

        String expected = """
                2026-09-03 12:34:56,789 metric       -   INFO - Starting metric script
                2026-09-03 12:34:56,789 metric       -  ERROR - failed
                2026-09-03 12:34:56,789 metric       - CRITICAL - fatal
                """;
        assertEquals(expected, stderr.toString(StandardCharsets.UTF_8));
        assertEquals(expected, Files.readString(file));
    }
}
