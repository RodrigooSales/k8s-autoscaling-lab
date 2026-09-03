package org.csajava.io;

import com.google.gson.Gson;

public final class JsonOut {
    private static final Gson GSON = new Gson();

    private JsonOut() {
    }

    public static void write(Object value) {
        System.out.print(GSON.toJson(value));
    }
}
