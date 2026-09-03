package org.csajava.io;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.Map;

public final class JsonOut {
    private static final Gson GSON = new Gson();

    private JsonOut() {
    }

    public static void write(Object value) {
        StringBuilder output = new StringBuilder();
        JsonElement json = value instanceof JsonElement element ? element : GSON.toJsonTree(value);
        append(json, output);
        System.out.print(output);
    }

    private static void append(JsonElement value, StringBuilder output) {
        if (value.isJsonObject()) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!first) {
                    output.append(", ");
                }
                first = false;
                appendString(entry.getKey(), output);
                output.append(": ");
                append(entry.getValue(), output);
            }
            output.append('}');
            return;
        }
        if (value.isJsonArray()) {
            output.append('[');
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                if (index > 0) {
                    output.append(", ");
                }
                append(value.getAsJsonArray().get(index), output);
            }
            output.append(']');
            return;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            appendString(value.getAsString(), output);
        } else {
            output.append(GSON.toJson(value));
        }
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || character >= 0x7f) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character);
                        output.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
