package org.csajava.util;

public final class CpuQuantity {
    private CpuQuantity() {
    }

    public static Integer parseToMilli(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        if (value.endsWith("m")) {
            String milli = value.substring(0, value.length() - 1);
            return parseInt(milli);
        }

        if (value.endsWith("n")) {
            String nanos = value.substring(0, value.length() - 1);
            try {
                int parsed = Integer.parseInt(nanos);
                return Math.max(1, parsed / 1_000_000);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        try {
            double cores = Double.parseDouble(value);
            return Math.max(1, (int) Math.round(cores * 1000.0));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatMilli(int milliCpu) {
        return milliCpu + "m";
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
