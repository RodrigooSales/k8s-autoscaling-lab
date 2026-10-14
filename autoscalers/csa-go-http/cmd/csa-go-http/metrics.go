package main

import (
	"context"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
	"sync"

	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/rest"
)

var deploymentResource = schema.GroupVersionResource{
	Group: "apps", Version: "v1", Resource: "deployments",
}

type metricCollectionConfig struct {
	namespace         string
	deploymentName    string
	metricName        string
	metricSelector    string
	targetMetricValue int64
}

type metricSnapshot struct {
	currentReplicas    int32
	currentMetricValue int64
	targetMetricValue  int64
}

type metricCollector struct {
	configuration  metricCollectionConfig
	loadRESTConfig func() (*rest.Config, error)
	clientOnce     sync.Once
	client         dynamic.Interface
	clientError    error
}

func metricCollectionConfigFromEnv() (metricCollectionConfig, error) {
	var configuration metricCollectionConfig
	var err error
	configuration.namespace, err = requiredEnvironmentValue("CSA_NAMESPACE")
	if err != nil {
		return metricCollectionConfig{}, err
	}
	configuration.deploymentName, err = requiredEnvironmentValue("CSA_DEPLOYMENT_NAME")
	if err != nil {
		return metricCollectionConfig{}, err
	}
	configuration.metricName, err = requiredEnvironmentValue("CSA_METRIC_NAME")
	if err != nil {
		return metricCollectionConfig{}, err
	}
	configuration.metricSelector = os.Getenv("CSA_METRIC_SELECTOR")
	targetValue := os.Getenv("CSA_TARGET_VALUE")
	if targetValue == "" {
		targetValue = "1000"
	}
	target, err := strconv.ParseInt(targetValue, 10, 64)
	if err != nil {
		return metricCollectionConfig{}, fmt.Errorf("CSA_TARGET_VALUE must be an integer: %w", err)
	}
	configuration.targetMetricValue = target
	return configuration, nil
}

func requiredEnvironmentValue(name string) (string, error) {
	value := os.Getenv(name)
	if strings.TrimSpace(value) == "" {
		return "", fmt.Errorf("%s must not be blank", name)
	}
	return value, nil
}

func newMetricCollector(configuration metricCollectionConfig) *metricCollector {
	return &metricCollector{
		configuration:  configuration,
		loadRESTConfig: rest.InClusterConfig,
	}
}

func (collector *metricCollector) collect(ctx context.Context) (metricSnapshot, error) {
	client, err := collector.kubernetesClient()
	if err != nil {
		return metricSnapshot{}, err
	}
	currentMetricValue, err := collector.readExternalMetric(ctx, client)
	if err != nil {
		return metricSnapshot{}, err
	}
	currentReplicas, err := collector.readDeploymentReplicas(ctx, client)
	if err != nil {
		return metricSnapshot{}, err
	}
	return metricSnapshot{
		currentReplicas:    currentReplicas,
		currentMetricValue: currentMetricValue,
		targetMetricValue:  collector.configuration.targetMetricValue,
	}, nil
}

func (collector *metricCollector) kubernetesClient() (dynamic.Interface, error) {
	collector.clientOnce.Do(func() {
		configuration, err := collector.loadRESTConfig()
		if err != nil {
			collector.clientError = fmt.Errorf("failed to load Kubernetes configuration: %w", err)
			return
		}
		collector.client, collector.clientError = dynamic.NewForConfig(configuration)
		if collector.clientError != nil {
			collector.clientError = fmt.Errorf("failed to create Kubernetes client: %w", collector.clientError)
		}
	})
	return collector.client, collector.clientError
}

func (collector *metricCollector) readExternalMetric(ctx context.Context, client dynamic.Interface) (int64, error) {
	metricResource := schema.GroupVersionResource{
		Group: "external.metrics.k8s.io", Version: "v1beta1", Resource: collector.configuration.metricName,
	}
	options := metav1.ListOptions{}
	if strings.TrimSpace(collector.configuration.metricSelector) != "" {
		options.LabelSelector = collector.configuration.metricSelector
	}
	metricList, err := client.Resource(metricResource).
		Namespace(collector.configuration.namespace).
		List(ctx, options)
	if err != nil {
		return 0, fmt.Errorf("failed to read external metric %s/%s: %w", collector.configuration.namespace, collector.configuration.metricName, err)
	}
	if len(metricList.Items) == 0 {
		return 0, fmt.Errorf("external metric %s/%s has no values", collector.configuration.namespace, collector.configuration.metricName)
	}

	var total int64
	for _, item := range metricList.Items {
		value, found, err := unstructured.NestedString(item.Object, "value")
		if err != nil || !found {
			return 0, fmt.Errorf("external metric response item has no value")
		}
		quantity, err := resource.ParseQuantity(value)
		if err != nil {
			return 0, fmt.Errorf("invalid external metric value %q: %w", value, err)
		}
		milliValue := quantity.MilliValue()
		if (milliValue > 0 && total > math.MaxInt64-milliValue) ||
			(milliValue < 0 && total < math.MinInt64-milliValue) {
			return 0, fmt.Errorf("external metric values exceed supported range")
		}
		total += milliValue
	}
	return total, nil
}

func (collector *metricCollector) readDeploymentReplicas(ctx context.Context, client dynamic.Interface) (int32, error) {
	deployment, err := client.Resource(deploymentResource).
		Namespace(collector.configuration.namespace).
		Get(ctx, collector.configuration.deploymentName, metav1.GetOptions{})
	if err != nil {
		return 0, fmt.Errorf("failed to read Deployment %s/%s: %w", collector.configuration.namespace, collector.configuration.deploymentName, err)
	}
	replicas, found, err := unstructured.NestedInt64(deployment.Object, "spec", "replicas")
	if err != nil || !found {
		return 0, fmt.Errorf("Deployment %s/%s has no configured replica count", collector.configuration.namespace, collector.configuration.deploymentName)
	}
	if replicas < 0 || replicas > math.MaxInt32 {
		return 0, fmt.Errorf("Deployment %s/%s has an invalid replica count", collector.configuration.namespace, collector.configuration.deploymentName)
	}
	return int32(replicas), nil
}
