package org.csajava.runtime.evaluate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class QuantityParityTest {
    @Test
    public void parsesValuesLikeKubernetesPython() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("1n", "0");
        cases.put("1u", "0");
        cases.put("1m", "0");
        cases.put("1k", "1000");
        cases.put("1K", "1000");
        cases.put("1M", "1000000");
        cases.put("1G", "1000000000");
        cases.put("1T", "1000000000000");
        cases.put("1P", "1000000000000000");
        cases.put("1E", "1000000000000000000");
        cases.put("1Ki", "1024");
        cases.put("1Mi", "1048576");
        cases.put("1Gi", "1073741824");
        cases.put("1Ti", "1099511627776");
        cases.put("1Pi", "1125899906842624");
        cases.put("1Ei", "1152921504606846976");
        cases.put("0.5Ki", "512");
        cases.put("-1500m", "-1");
        cases.put("1e3", "1000");

        cases.forEach((input, expected) -> assertEquals(input, expected, parse(input)));
    }

    @Test
    public void rejectsValuesRejectedByKubernetesPython() {
        for (String value : new String[] {"1000ki", "1000kb", "invalid"}) {
            assertThrows(value, IllegalArgumentException.class, () -> parse(value));
        }
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
