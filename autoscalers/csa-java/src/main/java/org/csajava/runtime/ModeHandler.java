package org.csajava.runtime;

import org.csajava.context.RuntimeContext;

@FunctionalInterface
public interface ModeHandler {
    void handle(RuntimeContext context);
}
