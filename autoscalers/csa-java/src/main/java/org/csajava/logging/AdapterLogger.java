package org.csajava.logging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AdapterLogger {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final String name;
    private final PrintStream stderr;
    private final Clock clock;
    private final BufferedWriter file;

    public AdapterLogger(String name) {
        this(
                name,
                Path.of(System.getProperty("csa.adapter.log", "/tmp/adapter.log")),
                System.err,
                Clock.systemDefaultZone());
    }

    AdapterLogger(String name, Path path, PrintStream stderr, Clock clock) {
        this.name = name;
        this.stderr = stderr;
        this.clock = clock;
        try {
            this.file = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("failed to open adapter log", error);
        }
    }

    public void info(String message) {
        write("INFO", message);
    }

    public void error(String message) {
        write("ERROR", message);
    }

    public void fatal(String message) {
        write("CRITICAL", message);
    }

    private synchronized void write(String level, String message) {
        String line = "%s %-12s - %6s - %s%n".formatted(
                LocalDateTime.now(clock).format(TIMESTAMP), name, level, message);
        try {
            file.write(line);
            file.flush();
        } catch (IOException error) {
            throw new IllegalStateException("failed to write adapter log", error);
        }
        stderr.print(line);
    }
}
