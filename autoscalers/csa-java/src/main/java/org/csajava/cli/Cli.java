package org.csajava.cli;

public record Cli(boolean valid, String error, Mode mode) {
    private static final String OPT_MODE = "-m";

    public static Cli parse(String[] args) {
        if (args == null || args.length != 2 || !OPT_MODE.equals(args[0])) {
            return invalid("usage: csa-java -m <metric|evaluate|adapt_replicas|adapt_tag|adapt_cpu>");
        }

        Mode mode = Mode.fromValue(args[1]);
        if (mode == null) {
            return invalid("invalid mode: " + args[1]);
        }

        return new Cli(true, "", mode);
    }

    public boolean isValid() {
        return valid;
    }

    private static Cli invalid(String error) {
        return new Cli(false, error, null);
    }
}
