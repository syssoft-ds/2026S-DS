package org.oxoo2a.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two complementary checks on {@link Simulator#stop()}:
 *
 * <ol>
 *   <li>Called from outside the simulation, it ends a node that would
 *       otherwise block in {@code receive()} forever.</li>
 *   <li>Called from inside an Actor's {@code engage()} loop, it fails
 *       fast — a real distributed system cannot be stopped by one node;
 *       termination must propagate via messages or come from an external
 *       trigger.</li>
 * </ol>
 */
class SimulatorStopTest {

    @Test
    @Timeout(5)
    void stopFromOutsideEndsBlockedNodes() {
        Simulator simulator = Simulator.getInstance();

        AtomicReference<ReceivedMessage> receivedAfterStop = new AtomicReference<>();
        AtomicBoolean listenerReturned = new AtomicBoolean(false);

        // No messages will ever be sent to this node — receive() blocks
        // forever unless the simulation is stopped.
        new Node("listener") {
            @Override
            protected void engage() {
                receivedAfterStop.set(receive());
                listenerReturned.set(true);
            }
        };

        // Trigger stop() from an external scheduler — never from a node.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.schedule(simulator::stop, 100, TimeUnit.MILLISECONDS);
            simulator.simulate();
        } finally {
            scheduler.shutdownNow();
            simulator.shutdown();
        }

        assertTrue(listenerReturned.get(),
                "listener's engage() must return once stop() is called");
        assertNull(receivedAfterStop.get(),
                "receive() must return null after stop()");
    }

    @Test
    @Timeout(5)
    void stopFromInsideAnEngageLoopThrows() {
        Simulator simulator = Simulator.getInstance();
        AtomicReference<Throwable> caught = new AtomicReference<>();

        // A node that misuses the API by trying to stop the simulation
        // from inside its own engage() — the framework must fail fast.
        new Node("violator") {
            @Override
            protected void engage() {
                try {
                    simulator.stop();
                } catch (Throwable t) {
                    caught.set(t);
                }
            }
        };

        simulator.simulate();
        simulator.shutdown();

        Throwable t = caught.get();
        assertNotNull(t, "stop() must throw when called from a node thread");
        assertInstanceOf(IllegalStateException.class, t);
    }
}
