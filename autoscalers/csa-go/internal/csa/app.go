// Package csa implements the CSA process modes and their runtime helpers.
package csa

import (
	"errors"
	"fmt"
	"io"
	"os"
)

const (
	defaultConfigPath = "/config.yaml"
	defaultLogPath    = "/tmp/adapter.log"
	usage             = "usage: csa-go -m <metric|evaluate|adapt_replicas|adapt_tag|adapt_cpu>"
)

// RunCLI executes a mode with the process streams and default runtime paths.
func RunCLI(args []string) int {
	return run(args, os.Stdin, os.Stdout, os.Stderr, defaultConfigPath, defaultLogPath)
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer, configPath, logPath string) int {
	return runWithDependencies(args, stdin, stdout, stderr, configPath, logPath, newInClusterKubernetesAPI, os.Getenv)
}

func runWithDependencies(
	args []string,
	stdin io.Reader,
	stdout, stderr io.Writer,
	configPath, logPath string,
	apiFactory func() (kubernetesAPI, error),
	env func(string) string,
) int {
	mode, err := parseMode(args)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 2
	}
	if mode != "metric" && mode != "evaluate" && mode != "adapt_replicas" && mode != "adapt_cpu" && mode != "adapt_tag" {
		fmt.Fprintf(stderr, "mode %q is not implemented yet\n", mode)
		return 1
	}
	input, err := io.ReadAll(stdin)
	if err != nil {
		fmt.Fprintf(stderr, "failed to read stdin: %v\n", err)
		return 1
	}
	if mode == "evaluate" {
		return runEvaluate(input, stdout, stderr, configPath, logPath, apiFactory, env)
	}
	if mode == "adapt_replicas" {
		return runAdaptReplicas(input, stdout, stderr, logPath, apiFactory)
	}
	if mode == "adapt_cpu" {
		return runAdaptCPU(input, stdout, stderr, configPath, logPath, apiFactory, env)
	}
	if mode == "adapt_tag" {
		return runAdaptTag(input, stdout, stderr, logPath, apiFactory, env)
	}
	// The metric phase intentionally does not load the Kubernetes configuration.

	logger, err := newAdapterLogger("metric", logPath, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "failed to create logger: %v\n", err)
		return 1
	}
	defer logger.close()

	output, err := metricResult(input, logger)
	if err != nil {
		fmt.Fprintf(stderr, "%v\n", err)
		return 1
	}
	if _, err := stdout.Write(output); err != nil {
		fmt.Fprintf(stderr, "failed to write stdout: %v\n", err)
		return 1
	}
	return 0
}

func parseMode(args []string) (string, error) {
	if len(args) != 2 || args[0] != "-m" {
		return "", errors.New(usage)
	}
	switch args[1] {
	case "metric", "evaluate", "adapt_replicas", "adapt_tag", "adapt_cpu":
		return args[1], nil
	default:
		return "", fmt.Errorf("invalid mode: %s", args[1])
	}
}
