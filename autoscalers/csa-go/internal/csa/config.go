package csa

import (
	"bytes"
	"fmt"
	"io"
	"os"

	"go.yaml.in/yaml/v2"
)

func loadConfig(path string, stderr io.Writer) (map[string]any, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(content)) == 0 {
		_, _ = io.WriteString(stderr, "config file was empty")
		return nil, nil
	}

	var config map[string]any
	if err := yaml.Unmarshal(content, &config); err != nil {
		_, _ = fmt.Fprintf(stderr, "failed to parse config file: %v", err)
		return nil, nil
	}
	if config == nil {
		_, _ = io.WriteString(stderr, "config file was empty")
		return nil, nil
	}
	return config, nil
}
