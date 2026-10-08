package org.csajava.runtime.evaluate;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;

public class EvaluateQuantityTest {
    @Test
    public void parsesMetricFormatsUsedByTheExperiment() {
        assertEquals("1000", parse("1000"));
        assertEquals("10000000", parse("10M"));
        assertEquals("10", parse("10000m"));
    }

    private static String parse(String value) {
        try {
            Method method = EvaluateRuntime.class.getDeclaredMethod("parseQuantityInt", String.class);
            method.setAccessible(true);
            return method.invoke(null, value).toString();
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
