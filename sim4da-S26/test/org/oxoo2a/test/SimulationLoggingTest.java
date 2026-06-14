package org.oxoo2a.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.Simulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link Simulator#disableLogging()} suppresses every
 * write to the log file — node registrations, simulation lifecycle
 * events, and any algorithm-level {@code log()} calls.
 */
class SimulationLoggingTest {

    @Test
    @Timeout(5)
    void disableLoggingProducesNoNewLogEvents() throws IOException {
        Simulator simulator = Simulator.getInstance();

        Path logFile = Path.of("sim4da-" + ProcessHandle.current().pid() + ".log");
        long sizeBefore = Files.exists(logFile) ? Files.size(logFile) : 0;

        simulator.disableLogging();

        // Create a node that does nothing but register and exit. Without
        // disableLogging() this would produce at least a "registered" line.
        new Node("ghost") {
            @Override
            protected void engage() { /* no-op */ }
        };

        simulator.simulate(1);
        simulator.shutdown();

        long sizeAfter = Files.exists(logFile) ? Files.size(logFile) : 0;
        assertEquals(sizeBefore, sizeAfter,
                "log file must not grow when logging is disabled");
    }
}
