package org.csajavahttp.metric;

public final class MetricCollectionException extends RuntimeException {
    public MetricCollectionException(String message) {
        super(message);
    }

    public MetricCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
