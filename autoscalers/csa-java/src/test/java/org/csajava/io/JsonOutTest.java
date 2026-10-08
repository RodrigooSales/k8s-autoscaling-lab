package org.csajava.io;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class JsonOutTest {
    @Test
    public void usesPythonJsonSpacing() {
        JsonObject value = new JsonObject();
        value.addProperty("cpu", 650);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            JsonOut.write(value);
        } finally {
            System.setOut(original);
        }

        assertEquals("{\"cpu\": 650}", bytes.toString(StandardCharsets.UTF_8));
    }
}
