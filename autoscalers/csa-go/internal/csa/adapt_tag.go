package csa

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"unicode"
	"unicode/utf8"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

var tagLadder = []string{"100k", "200k", "400k", "600k", "800k"}

type parsedImageReference struct {
	repository string
	image      string
	tag        string
	hasTag     bool
}

func runAdaptTag(
	input []byte,
	stdout, stderr io.Writer,
	logPath string,
	apiFactory func() (kubernetesAPI, error),
	env func(string) string,
) int {
	logger, err := newAdapterLogger("adapt_tag", logPath, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "failed to create logger: %v\n", err)
		return 1
	}
	defer logger.close()

	spec, err := decodeAdaptationSpec(input)
	if err != nil {
		_ = logger.error("Invalid JSON on stdin: " + err.Error())
		return 0
	}
	name, namespace, ok := adaptationIdentity(spec)
	if !ok {
		_ = logger.error("Spec must include resource.metadata.name and resource.metadata.namespace")
		return 0
	}
	api, err := apiFactory()
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to load in-cluster config: %v", err))
		return 0
	}
	deployment, err := api.readDeployment(context.Background(), name, namespace)
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to read Deployment %s/%s: %v", namespace, name, err))
		return 0
	}
	if deployment == nil || deployment.object == nil {
		_ = logger.error(fmt.Sprintf("Deployment %s/%s not found", namespace, name))
		return 0
	}

	parameters := adaptationParameters(spec)
	tagUp, exists := parameters[parameterTagUp]
	if !exists {
		_ = logger.error(fmt.Sprintf("Parameters must include '%s' (bool)", parameterTagUp))
		return 0
	}
	updateCPU := pythonTruthy(parameters[parameterUpdate])
	if rolloutInProgress(deployment.object) {
		_ = logger.info("Rollout in progress, skipping deployment patch")
		return writeJSONResult(stdout, map[string]any{"result": "skip"})
	}

	container := findDeploymentContainer(deployment.object, "znn")
	if container == nil {
		_ = logger.error("Container 'znn' not found in deployment")
		return 0
	}
	parts, ok := imageReferenceParts(container.Image)
	if !ok || !parts.hasTag {
		_ = logger.fatal(fmt.Sprintf("Could not identify tag in current container image: %s", container.Image))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	currentTag := parts.tag
	store := &initialDataStore{api: api, logger: logger, env: env}
	if err := store.storeTag(currentTag); err != nil {
		return adaptationRuntimeError(logger, err)
	}
	goingUp := pythonTruthy(tagUp)
	if goingUp {
		initialTag, err := store.getStoredData("tag")
		if err != nil {
			return adaptationRuntimeError(logger, err)
		}
		if tagInLadder(initialTag) && initialTag == currentTag {
			_ = logger.info(fmt.Sprintf("%s is the initial tag, not adapting above it", currentTag))
			return writeJSONResult(stdout, map[string]any{"result": "skip"})
		}
	}

	newTag, canChange := adjacentTag(currentTag, goingUp)
	if !canChange {
		direction := "down"
		if goingUp {
			direction = "up"
		}
		_ = logger.info(fmt.Sprintf("No change possible for tag %s direction %s", currentTag, direction))
		return writeJSONResult(stdout, map[string]any{"result": "skip"})
	}
	container.Image = replaceImageTag(container.Image, newTag)
	_ = logger.info(fmt.Sprintf("Adapting tag to %s", newTag))

	if updateCPU {
		initialCPU, err := store.getStoredData("cpu_limit")
		if err != nil {
			return adaptationRuntimeError(logger, err)
		}
		if initialCPU == nil {
			specCPU, err := deploymentSpecMCPU(deployment)
			if err != nil {
				return adaptationRuntimeError(logger, err)
			}
			initialCPU = specCPU
			if err := store.storeCPU(specCPU); err != nil {
				return adaptationRuntimeError(logger, err)
			}
		}
		currentCPU, found, err := currentDeploymentCPUMilli(api, deployment, namespace)
		if err != nil {
			return adaptationRuntimeError(logger, err)
		}
		if !found {
			currentCPU = 0
		}
		if currentCPU == 0 {
			_ = logger.info("Could not read current mcpu")
		} else if !pythonNumberEqual(initialCPU, currentCPU) {
			if err := setDeploymentCPU(deployment, currentCPU); err != nil {
				return adaptationRuntimeError(logger, err)
			}
		}
	}

	body, err := json.Marshal(deployment.object)
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to patch Deployment %s/%s: %v", namespace, name, err))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	if err := api.patchDeployment(context.Background(), name, namespace, body); err != nil {
		_ = logger.error(fmt.Sprintf("Failed to patch Deployment %s/%s: %v", namespace, name, err))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	return writeJSONResult(stdout, map[string]any{"tag": newTag})
}

func imageReferenceParts(image string) (parsedImageReference, bool) {
	value := image
	if strings.HasPrefix(value, "/") {
		value = value[1:]
	}
	lastSlash := strings.LastIndex(value, "/")
	lastColon := strings.LastIndex(value, ":")
	parts := parsedImageReference{}
	if lastColon > lastSlash {
		parts.tag = value[lastColon+1:]
		parts.hasTag = true
		value = value[:lastColon]
	}
	if parts.hasTag && !validTag(parts.tag) {
		return parsedImageReference{}, false
	}
	segments := strings.Split(value, "/")
	if len(segments) < 1 || len(segments) > 3 {
		return parsedImageReference{}, false
	}
	for _, segment := range segments {
		if segment == "" {
			return parsedImageReference{}, false
		}
	}
	if len(segments) == 3 {
		if !validRepository(segments[0]) {
			return parsedImageReference{}, false
		}
		parts.repository = segments[0]
		segments = segments[1:]
	}
	for _, segment := range segments {
		if !validImageSegment(segment) {
			return parsedImageReference{}, false
		}
	}
	parts.image = strings.Join(segments, "/")
	return parts, true
}

func replaceImageTag(image, tag string) string {
	parts, ok := imageReferenceParts(image)
	if !ok {
		return image
	}
	prefix := ""
	if parts.repository != "" {
		prefix = parts.repository + "/"
	}
	return prefix + parts.image + ":" + tag
}

func adjacentTag(tag string, up bool) (string, bool) {
	for index, candidate := range tagLadder {
		if candidate != tag {
			continue
		}
		next := index - 1
		if up {
			next = index + 1
		}
		if next < 0 || next >= len(tagLadder) {
			return "", false
		}
		return tagLadder[next], true
	}
	return "", false
}

func findDeploymentContainer(deployment *appsv1.Deployment, name string) *corev1.Container {
	for index := range deployment.Spec.Template.Spec.Containers {
		if deployment.Spec.Template.Spec.Containers[index].Name == name {
			return &deployment.Spec.Template.Spec.Containers[index]
		}
	}
	return nil
}

func tagInLadder(value any) bool {
	tag, ok := value.(string)
	if !ok {
		return false
	}
	for _, item := range tagLadder {
		if item == tag {
			return true
		}
	}
	return false
}

func currentDeploymentCPUMilli(api kubernetesAPI, deployment *deploymentSnapshot, namespace string) (int64, bool, error) {
	selector, err := deploymentLabelSelector(deployment.object)
	if err != nil {
		return 0, false, err
	}
	pods, err := api.listPods(context.Background(), namespace, selector)
	if err != nil {
		return 0, false, err
	}
	if pods == nil || len(pods.items) == 0 {
		return 0, false, nil
	}
	return currentPodMCPU(pods)
}

func pythonNumberEqual(initial any, current int64) bool {
	value, err := numberAsFloat64(initial)
	return err == nil && value == float64(current)
}

func setDeploymentCPU(deployment *deploymentSnapshot, current int64) error {
	containers := deployment.object.Spec.Template.Spec.Containers
	for index := range containers {
		if index >= len(deployment.containerResourcesPresent) || !deployment.containerResourcesPresent[index] {
			return fmt.Errorf("container %s has no resources", containers[index].Name)
		}
		if containers[index].Resources.Limits == nil {
			return fmt.Errorf("container %s has no CPU limits map", containers[index].Name)
		}
		containers[index].Resources.Limits[corev1.ResourceCPU] = resource.MustParse(formatCPUMilli(current))
	}
	return nil
}

func validRepository(repository string) bool {
	host, port, hasPort := strings.Cut(repository, ":")
	if hasPort {
		if port == "" {
			return false
		}
		for _, char := range port {
			if char < '0' || char > '9' {
				return false
			}
		}
	}
	if host == "" {
		return false
	}
	for _, char := range host {
		if !pythonWord(char) && char != '.' && char != '-' {
			return false
		}
	}
	return true
}

func validImageSegment(segment string) bool {
	for _, char := range segment {
		if char != '.' && char != '-' && char != '_' && (char < 'a' || char > 'z') && (char < '0' || char > '9') {
			return false
		}
	}
	return segment != ""
}

func validTag(tag string) bool {
	if tag == "" || utf8.RuneCountInString(tag) > 127 {
		return false
	}
	for _, char := range tag {
		if !pythonWord(char) && char != '.' && char != '-' {
			return false
		}
	}
	return true
}

func pythonWord(value rune) bool {
	return value == '_' || unicode.IsLetter(value) || unicode.IsNumber(value)
}
