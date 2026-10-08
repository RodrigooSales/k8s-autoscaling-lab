package org.csajava.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CpuQuantityTest {
    @Test
    public void parsesMilliValue() {
        assertEquals(Integer.valueOf(750), CpuQuantity.parseToMilli("750m"));
    }

    @Test
    public void parsesCoreValue() {
        assertEquals(Integer.valueOf(500), CpuQuantity.parseToMilli("0.5"));
    }

    @Test
    public void parsesNanoValue() {
        assertEquals(Integer.valueOf(1), CpuQuantity.parseToMilli("900000n"));
    }

    @Test
    public void rejectsInvalidValue() {
        assertNull(CpuQuantity.parseToMilli("abc"));
    }

    @Test
    public void formatsMilliValue() {
        assertEquals("650m", CpuQuantity.formatMilli(650));
    }
}
