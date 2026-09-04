package org.csajava;

import com.google.gson.JsonSyntaxException;
import org.csajava.cli.Cli;
import org.csajava.cli.Mode;
import org.csajava.context.RuntimeContext;
import org.csajava.io.Stdin;
import org.csajava.logging.AdapterLogger;
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
        } catch (JsonSyntaxException error) {
            return malformedJson(cli.mode(), error);
        } catch (RuntimeException e) {
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static int malformedJson(Mode mode, JsonSyntaxException error) {
        String message = "Invalid JSON on stdin: " + error.getMessage();
        if (mode == Mode.METRIC) {
            error.printStackTrace(System.err);
            return 1;
        }
        if (mode == Mode.EVALUATE) {
            new AdapterLogger("eval_main").info("evaluate main");
            new AdapterLogger("evaluate").error(message);
            error.printStackTrace(System.err);
            return 1;
        }

        String loggerName = switch (mode) {
            case ADAPT_REPLICAS -> "adapt_repl";
            case ADAPT_CPU -> "adapt_cpu";
            case ADAPT_TAG -> "adapt_tag";
            default -> throw new IllegalStateException("unexpected mode: " + mode);
        };
        new AdapterLogger(loggerName).error(message);
        return 0;
    }
}
