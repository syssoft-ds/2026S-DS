package org.oxoo2a.test;

import org.junit.jupiter.api.Test;
import org.oxoo2a.sim4da.RandomValues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression test for {@link RandomValues}. Before this test existed,
 * {@code getDouble} silently ignored its configured distribution function
 * and used {@code Math.random()} instead — meaning every
 * {@code SimulationBehavior} extension that supplied a custom distribution
 * had no effect.
 */
class RandomValuesTest {

    @Test
    void getDoubleUsesConfiguredDistribution() {
        RandomValues midpoint = new RandomValues(() -> 0.5);
        assertEquals(50.0, midpoint.getDouble(0, 100), 1e-9);
        assertEquals(50.0, midpoint.getDouble(0, 100), 1e-9);
    }

    @Test
    void getDoubleScalesLinearly() {
        assertEquals(10.0, new RandomValues(() -> 0.0).getDouble(10, 20), 1e-9);
        assertEquals(20.0, new RandomValues(() -> 1.0).getDouble(10, 20), 1e-9);
        assertEquals(15.0, new RandomValues(() -> 0.5).getDouble(10, 20), 1e-9);
    }

    @Test
    void getLongFloorsTheScaledValue() {
        RandomValues r = new RandomValues(() -> 0.999);
        assertEquals(9L, r.getLong(0, 10));
    }

    @Test
    void getDoubleThrowsWhenSupplierOutOfRange() {
        assertThrows(IllegalStateException.class,
                () -> new RandomValues(() -> 1.5).getDouble(0, 100));
        assertThrows(IllegalStateException.class,
                () -> new RandomValues(() -> -0.5).getDouble(0, 100));
    }
}
