package csa

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"

	corev1 "k8s.io/api/core/v1"
)

const (
	initialDataAnnotation   = "csa.custom-self-adapter.net/initialData"
	initialDataStatusKey    = "initialData"
	csaNameEnvironment      = "CSA_NAME"
	csaNamespaceEnvironment = "CSA_NAMESPACE"
)

type initialDataStore struct {
	api    kubernetesAPI
	logger *adapterLogger
	env    func(string) string
}

func (store *initialDataStore) getStoredCPU() (int64, error) {
	value, err := store.getStoredData("cpu_limit")
	if err != nil {
		return 0, err
	}
	if !pythonTruthy(value) {
		return 0, nil
	}
	cpu, err := numberAsInt64(value)
	if err != nil {
		return 0, fmt.Errorf("invalid stored cpu_limit: %w", err)
	}
	return cpu, nil
}

func (store *initialDataStore) storeCPU(cpuLimit any) error {
	stored, err := store.getStoredData("cpu_limit")
	if err != nil {
		return err
	}
	if stored != nil && stored != "" {
		return nil
	}
	return store.setSelfAnnotation(map[string]any{"cpu_limit": cpuLimit})
}

func (store *initialDataStore) storeTag(tag string) error {
	stored, err := store.getStoredData("tag")
	if err != nil {
		return err
	}
	if stored != nil && stored != "" {
		return nil
	}
	return store.setSelfAnnotation(map[string]any{"tag": tag})
}

func (store *initialDataStore) getStoredData(parameter string) (any, error) {
	status, err := store.getCSAStatus()
	if err != nil {
		return nil, err
	}
	if status == nil {
		_ = store.logger.infoAs("initial_data", "csa_status is None")
		return "", nil
	}
	serialized, exists := status[initialDataStatusKey]
	if !exists {
		_ = store.logger.infoAs("initial_data", initialDataStatusKey+" not found in csa_status")
		return "", nil
	}
	encoded, ok := serialized.(string)
	if !ok {
		return nil, fmt.Errorf("status.%s must be a JSON string", initialDataStatusKey)
	}
	decoder := json.NewDecoder(bytes.NewBufferString(encoded))
	decoder.UseNumber()
	var initialData map[string]any
	if err := decoder.Decode(&initialData); err != nil {
		return nil, fmt.Errorf("invalid status.%s JSON: %w", initialDataStatusKey, err)
	}
	if initialData == nil {
		return nil, fmt.Errorf("status.%s JSON must be an object", initialDataStatusKey)
	}
	value, exists := initialData[parameter]
	if !exists {
		return "", nil
	}
	return value, nil
}

func (store *initialDataStore) setSelfAnnotation(value map[string]any) error {
	pod, err := store.getSelfPod()
	if err != nil {
		return err
	}
	if pod == nil {
		_ = store.logger.errorAs("initial_data", "Could not find CSA pod")
		return nil
	}

	status, err := store.getCSAStatus()
	if err != nil {
		return err
	}
	_ = store.logger.infoAs("initial_data", fmt.Sprint(status))
	_ = store.logger.infoAs("initial_data", fmt.Sprint(value))
	if status != nil {
		if serialized, exists := status[initialDataStatusKey]; exists {
			encoded, ok := serialized.(string)
			if !ok {
				return fmt.Errorf("status.%s must be a JSON string", initialDataStatusKey)
			}
			var previous map[string]any
			decoder := json.NewDecoder(bytes.NewBufferString(encoded))
			decoder.UseNumber()
			if err := decoder.Decode(&previous); err != nil {
				return fmt.Errorf("invalid status.%s JSON: %w", initialDataStatusKey, err)
			}
			if previous == nil {
				return fmt.Errorf("status.%s JSON must be an object", initialDataStatusKey)
			}
			for key, item := range value {
				previous[key] = item
			}
			value = previous
			_ = store.logger.infoAs("initial_data", fmt.Sprint(value))
		}
	}

	// The Python reference creates a local annotations map but does not attach it
	// to the Pod when metadata.annotations is nil. Preserve that behavior.
	if pod.Annotations != nil {
		encoded, err := json.Marshal(value)
		if err != nil {
			return fmt.Errorf("failed to encode initialData annotation: %w", err)
		}
		pod.Annotations[initialDataAnnotation] = string(encoded)
	}
	if err := store.api.patchPod(context.Background(), pod.Name, pod.Namespace, pod); err != nil {
		return err
	}
	return nil
}

func (store *initialDataStore) getSelfPod() (*corev1.Pod, error) {
	name, namespace, ok := store.identity()
	if !ok {
		return nil, nil
	}
	pod, err := store.api.readPod(context.Background(), name, namespace)
	if err != nil {
		return nil, err
	}
	if pod == nil {
		_ = store.logger.errorAs("initial_data", fmt.Sprintf("Pod %s/%s not found!", namespace, name))
		return nil, nil
	}
	return pod, nil
}

func (store *initialDataStore) getCSAStatus() (map[string]any, error) {
	name, namespace, ok := store.identity()
	if !ok {
		return nil, nil
	}
	return store.api.getCSAStatus(context.Background(), name, namespace)
}

func (store *initialDataStore) identity() (string, string, bool) {
	name := store.env(csaNameEnvironment)
	namespace := store.env(csaNamespaceEnvironment)
	if name == "" || namespace == "" {
		_ = store.logger.errorAs("initial_data", "CSA_NAME or CSA_NAMESPACE not defined!")
		return "", "", false
	}
	return name, namespace, true
}

func pythonTruthy(value any) bool {
	switch value := value.(type) {
	case nil:
		return false
	case bool:
		return value
	case string:
		return value != ""
	case json.Number:
		if integer, err := value.Int64(); err == nil {
			return integer != 0
		}
		if number, err := value.Float64(); err == nil {
			return number != 0
		}
		return true
	case int:
		return value != 0
	case int64:
		return value != 0
	case float64:
		return value != 0
	case []any:
		return len(value) != 0
	case map[string]any:
		return len(value) != 0
	default:
		return true
	}
}
