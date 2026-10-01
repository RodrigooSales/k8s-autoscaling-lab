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
                long parsed = Long.parseLong(nanos);
                long milli = Math.floorDiv(parsed, 1_000_000L);
                return milli > Integer.MAX_VALUE ? null : (int) Math.max(1L, milli);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        try {
            double cores = Double.parseDouble(value);
            double milli = Math.rint(cores * 1000.0);
            if (!Double.isFinite(milli) || milli > Integer.MAX_VALUE || milli < Integer.MIN_VALUE) {
                return null;
            }
            return Math.max(1, (int) milli);
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
