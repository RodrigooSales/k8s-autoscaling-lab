package org.csajava.cli;

import java.util.Arrays;

public enum Mode {
    METRIC("metric"),
    EVALUATE("evaluate"),
    ADAPT_REPLICAS("adapt_replicas"),
    ADAPT_TAG("adapt_tag"),
    ADAPT_CPU("adapt_cpu");

    private final String value;

    Mode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Mode fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
