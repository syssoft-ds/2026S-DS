package org.oxoo2a.sim4da;

import java.util.function.Supplier;

public class SimulationBehavior {

    // Distribution function for the selection of the next message in a message queue
    private static RandomValues messageQueueSelector = null;
    public static void setMessageQueueSelectionDistributionFunction ( Supplier<Double> distributionFunction ) {
        if (messageQueueSelector != null) {
            throw new OverwriteDistributionFunctionException(
                    "Distribution function for message queue selection has already been set");
        }
        messageQueueSelector = new RandomValues(distributionFunction);
    }
    public static int selectMessageInQueue ( int queueSize ) {
        assert(queueSize > 0);
        if (messageQueueSelector == null) {
            return 0;
        }
        else {
            return (int) messageQueueSelector.getLong(0, queueSize-1);
        }
    }

    /** Resets all configurable behavior. Called from {@link Simulator#shutdown()}. */
    public static void reset() {
        messageQueueSelector = null;
    }
}
