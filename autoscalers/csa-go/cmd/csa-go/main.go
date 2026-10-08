package main

import (
	"os"

	"github.com/RodrigooSales/k8s-autoscaling-lab/autoscalers/csa-go/internal/csa"
)

func main() {
	os.Exit(csa.RunCLI(os.Args[1:]))
}
