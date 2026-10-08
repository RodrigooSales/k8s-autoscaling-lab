package org.csajava.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CliTest {
    @Test
    public void parsesValidMetricMode() {
        Cli cli = Cli.parse(new String[] {"-m", "metric"});

        assertTrue(cli.isValid());
        assertEquals(Mode.METRIC, cli.mode());
    }

    @Test
    public void rejectsUnknownMode() {
        Cli cli = Cli.parse(new String[] {"-m", "x"});

        assertFalse(cli.isValid());
        assertEquals("invalid mode: x", cli.error());
    }

    @Test
    public void rejectsWrongArity() {
        Cli cli = Cli.parse(new String[] {"-m"});

        assertFalse(cli.isValid());
        assertTrue(cli.error().startsWith("usage:"));
    }
}
