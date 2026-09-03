package org.csajava;

import org.csajava.cli.Cli;
import org.csajava.context.RuntimeContext;
import org.csajava.io.Stdin;
import org.csajava.runtime.ModeHandler;
import org.csajava.runtime.ModeHandlers;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, "/config.yaml");
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, String configPath) {
        Cli cli = Cli.parse(args);
        if (!cli.isValid()) {
            System.err.println(cli.error());
            return 2;
        }

        try {
            String stdin = Stdin.readAll();
            RuntimeContext context = RuntimeContext.load(configPath, stdin);
            ModeHandler handler = ModeHandlers.forMode(cli.mode());

            if (handler == null) {
                System.err.println("unsupported mode: " + cli.mode());
                return 2;
            }

            handler.handle(context);
            return 0;
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            return 1;
        }
    }
}
