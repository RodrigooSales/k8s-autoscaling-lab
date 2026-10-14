package org.csajavahttp.metric;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Deployment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public class MetricCollectionService {
    private static final String EXTERNAL_METRICS_GROUP = "external.metrics.k8s.io";
    private static final String EXTERNAL_METRICS_VERSION = "v1beta1";
    private static final BigDecimal MILLI = BigDecimal.valueOf(1000);

    private final String namespace;
    private final String deploymentName;
    private final String metricName;
    private final String metricSelector;
    private final String targetValue;
    private final AppsV1Api deploymentApi;
    private final CustomObjectsApi customObjects;

    @Autowired
    public MetricCollectionService(
            ApiClient client,
            @Value("${csa.namespace}") String namespace,
            @Value("${csa.deployment-name}") String deploymentName,
            @Value("${csa.metric-name}") String metricName,
            @Value("${csa.metric-selector:}") String metricSelector,
            @Value("${csa.target-value}") String targetValue) {
        this(namespace, deploymentName, metricName, metricSelector, targetValue, client);
    }

    MetricCollectionService(
            String namespace,
            String deploymentName,
            String metricName,
            String metricSelector,
            String targetValue,
            ApiClient client) {
        this.namespace = required(namespace, "namespace");
        this.deploymentName = required(deploymentName, "deployment name");
        this.metricName = required(metricName, "metric name");
        this.metricSelector = Objects.requireNonNullElse(metricSelector, "");
        this.targetValue = required(targetValue, "target value");
        this.deploymentApi = new AppsV1Api(client);
        this.customObjects = new CustomObjectsApi(client);
    }

    public MetricSnapshot collect() {
        BigInteger targetMetricValue = readTargetMetricValue();
        BigInteger currentValue = readExternalMetricValue();
        int replicas = readDeploymentReplicas();
        return new MetricSnapshot(replicas, currentValue, targetMetricValue);
    }

    private BigInteger readTargetMetricValue() {
        try {
            return quantityValue(targetValue, "configured target").toBigIntegerExact();
        } catch (ArithmeticException error) {
            throw new MetricCollectionException("Configured target metric must be an integer", error);
        }
    }

    private int readDeploymentReplicas() {
        V1Deployment deployment;
        try {
            deployment = deploymentApi.readNamespacedDeployment(deploymentName, namespace).execute();
        } catch (ApiException | IllegalArgumentException error) {
            throw new MetricCollectionException(
                    "Failed to read Deployment " + namespace + "/" + deploymentName, error);
        }

        if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getReplicas() == null) {
            throw new MetricCollectionException(
                    "Deployment " + namespace + "/" + deploymentName
                            + " has no configured replica count");
        }
        return deployment.getSpec().getReplicas();
    }

    private BigInteger readExternalMetricValue() {
        Object response;
        try {
            var request = customObjects.listNamespacedCustomObject(
                    EXTERNAL_METRICS_GROUP,
                    EXTERNAL_METRICS_VERSION,
                    namespace,
                    metricName);
            if (!metricSelector.isBlank()) {
                request.labelSelector(metricSelector);
            }
            response = request.execute();
        } catch (ApiException | IllegalArgumentException error) {
            throw new MetricCollectionException(
                    "Failed to read external metric " + namespace + "/" + metricName, error);
        }

        if (!(response instanceof Map<?, ?> metricList) || !(metricList.get("items") instanceof List<?> items)) {
            throw new MetricCollectionException("External metric response has no items list");
        }
        if (items.isEmpty()) {
            throw new MetricCollectionException(
                    "External metric " + namespace + "/" + metricName + " has no values");
        }

        BigInteger total = BigInteger.ZERO;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> metric) || !metric.containsKey("value")) {
                throw new MetricCollectionException("External metric response item has no value");
            }
            total = total.add(milliValue(metric.get("value")));
        }
        return total;
    }

    private static BigInteger milliValue(Object value) {
        BigDecimal scaled = quantityValue(value, "external metric value")
                .multiply(MILLI)
                .setScale(0, RoundingMode.CEILING);
        return scaled.toBigIntegerExact();
    }

    private static BigDecimal quantityValue(Object value, String field) {
        if (!(value instanceof String quantity)) {
            throw new MetricCollectionException("Invalid " + field + " quantity: " + value);
        }
        try {
            return new Quantity(quantity).getNumber();
        } catch (RuntimeException error) {
            throw new MetricCollectionException("Invalid " + field + " quantity: " + value, error);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CSA " + name + " must not be blank");
        }
        return value;
    }

    public record MetricSnapshot(
            int currentReplicas,
            BigInteger currentMetricValue,
            BigInteger targetMetricValue) {
    }
}
