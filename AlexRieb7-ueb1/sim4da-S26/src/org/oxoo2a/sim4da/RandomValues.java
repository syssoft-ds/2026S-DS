package org.oxoo2a.sim4da;

import java.util.function.Supplier;

public class RandomValues {

    public RandomValues ( Supplier<Double> distributionFunction ) {
        this.distributionFunction = distributionFunction;
    }

    public double getDouble( double minValue, double maxValue ) {
        double v = distributionFunction.get();
        if (v < 0 || v > 1) {
            throw new IllegalStateException(
                    "Distribution function returned " + v + " — must be in [0, 1]");
        }
        return minValue + v * (maxValue - minValue);
    }

    public long getLong ( long minValue, long maxValue ) {
        return (long) getDouble(minValue, maxValue);
    }

    public static Supplier<Double> getUniformDistribution() {
        return Math::random;
    }
    public static Supplier<Double> getNormalDistribution(double mean, double stdDev) {
        // Using the Box-Muller transform
        return () -> {
            double u, v, s;
            do {
                u = Math.random() * 2 - 1;
                v = Math.random() * 2 - 1;
                s = u * u + v * v;
            } while (s >= 1 || s == 0);
            double mul = Math.sqrt(-2.0 * Math.log(s) / s);
            return mean + stdDev * u * mul;
        };
    }

    private Supplier<Double> distributionFunction;
}