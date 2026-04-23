package org.csajava.util;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class YamlMap {
    private YamlMap() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof List<?> raw) {
            return (List<Object>) raw;
        }
        return Collections.emptyList();
    }

    public static Integer integer(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    public static Boolean bool(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    public static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof String s) {
            return s;
        }
        return null;
    }
}
