package org.csajava;

import org.csajava.cli.Cli;
import org.csajava.context.RuntimeContext;
import org.csajava.io.JsonOut;
import org.csajava.io.Stdin;
import org.csajava.model.ResultError;
import org.csajava.runtime.ModeHandler;
import org.csajava.runtime.ModeHandlers;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        Cli cli = Cli.parse(args);
        if (!cli.isValid()) {
            System.err.println(cli.error());
            System.exit(2);
            return;
        }

        try {
            String stdin = Stdin.readAll();
            RuntimeContext context = RuntimeContext.load("/config.yaml", stdin);
            ModeHandler handler = ModeHandlers.forMode(cli.mode());

            if (handler == null) {
                JsonOut.write(new ResultError("error", "unsupported mode: " + cli.mode()));
                System.exit(2);
                return;
            }

            handler.handle(context);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "runtime failure" : e.getMessage();
            JsonOut.write(new ResultError("error", message));
            System.exit(1);
        }
    }
}
