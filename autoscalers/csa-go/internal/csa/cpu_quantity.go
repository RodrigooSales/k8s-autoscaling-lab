package csa

import (
	"fmt"
	"math"
	"math/big"
	"strconv"
	"strings"

	"k8s.io/apimachinery/pkg/api/resource"
)

func parseCPUToMilli(cpu string) (int64, bool) {
	value := strings.TrimSpace(cpu)
	if value == "" {
		return 0, false
	}
	if strings.HasSuffix(value, "m") {
		milli, err := strconv.ParseInt(strings.TrimSuffix(value, "m"), 10, 64)
		return milli, err == nil
	}
	if strings.HasSuffix(value, "n") {
		nanos, err := strconv.ParseInt(strings.TrimSuffix(value, "n"), 10, 64)
		if err != nil {
			return 0, false
		}
		milli := nanos / 1_000_000
		if milli < 1 {
			milli = 1
		}
		return milli, true
	}

	cores, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsNaN(cores) || math.IsInf(cores, 0) {
		return 0, false
	}
	milli := math.RoundToEven(cores * 1000)
	if milli < 1 {
		milli = 1
	}
	if milli > math.MaxInt64 || milli < math.MinInt64 {
		return 0, false
	}
	return int64(milli), true
}

func parseQuantityInteger(value string) (*big.Int, error) {
	quantity, err := resource.ParseQuantity(value)
	if err != nil {
		return nil, fmt.Errorf("invalid quantity %q: %w", value, err)
	}

	decimal := quantity.AsDec()
	integer := new(big.Int).Set(decimal.UnscaledBig())
	scale := int(decimal.Scale())
	if scale > 0 {
		integer.Quo(integer, new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil))
	} else if scale < 0 {
		integer.Mul(integer, new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(-scale)), nil))
	}
	return integer, nil
}
