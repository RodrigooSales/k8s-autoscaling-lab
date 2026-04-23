package org.csajava.runtime;

import org.csajava.cli.Mode;
import org.csajava.io.JsonOut;
import org.csajava.runtime.adapt.cpu.AdaptCpuRuntime;
import org.csajava.runtime.adapt.replicas.AdaptReplicasRuntime;
import org.csajava.runtime.adapt.tag.AdaptTagRuntime;
import org.csajava.runtime.evaluate.EvaluateRuntime;
import org.csajava.runtime.metric.MetricRuntime;

public final class ModeHandlers {
    private ModeHandlers() {
    }

    public static ModeHandler forMode(Mode mode) {
        if (mode == null) {
            return null;
        }

        return switch (mode) {
            case METRIC -> context -> JsonOut.write(MetricRuntime.evaluate(context));
            case EVALUATE -> context -> JsonOut.write(EvaluateRuntime.evaluate(context));
            case ADAPT_REPLICAS -> context -> JsonOut.write(AdaptReplicasRuntime.evaluate(context));
            case ADAPT_TAG -> context -> JsonOut.write(AdaptTagRuntime.evaluate(context));
            case ADAPT_CPU -> context -> JsonOut.write(AdaptCpuRuntime.evaluate(context));
        };
    }
}
