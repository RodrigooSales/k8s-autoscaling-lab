package org.csajava.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Stdin {
    private Stdin() {
    }

    public static String readAll() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read stdin", e);
        }
    }
}
