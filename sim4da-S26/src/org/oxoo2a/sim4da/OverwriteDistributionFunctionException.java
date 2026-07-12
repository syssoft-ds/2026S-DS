package org.oxoo2a.sim4da;

/**
 * Thrown when a {@link SimulationBehavior} configuration slot is set
 * twice. Setup-time programmer error: unchecked, so callers don't need
 * try/catch around their configuration code.
 */
public class OverwriteDistributionFunctionException extends RuntimeException {
    public OverwriteDistributionFunctionException(String details) {
        super(details);
    }
}
