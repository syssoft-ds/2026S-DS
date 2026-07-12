package org.oxoo2a.sim4da;

import org.oxoo2a.sim4da.internal.EventLog;
import org.oxoo2a.sim4da.internal.Network;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lifecycle controller for a sim4da run. There is exactly one Simulator
 * per program — students don't need more than that, and the singleton
 * makes the constraint explicit.
 *
 * <p>Typical lifecycle:
 *
 * <pre>{@code
 *     Simulator simulator = Simulator.getInstance();
 *     // ... create your nodes ...
 *     simulator.simulate();          // or simulate(long durationInSeconds)
 *     simulator.shutdown();          // resets framework state for the next run
 * }</pre>
 *
 * <p>{@link #stop()} ends the simulation early but must be called from
 * <em>outside</em> the simulation — the test thread or an external
 * scheduler — never from inside an Actor's {@code engage()} loop. A
 * real distributed system cannot be stopped by one node; termination
 * has to propagate via messages or arrive from an external trigger.
 * {@link #shutdown()} resets all framework state, so a JUnit suite that
 * runs many tests in the same JVM remains independent.
 */
public class Simulator {
    private final String version = "sim4da Summer 2026";
    private final AtomicLong simulatorSeq = new AtomicLong();
    private  Simulator () {
        System.out.println(version);
    }

    private void log(String event) {
        EventLog.getInstance().record("Simulator", simulatorSeq.incrementAndGet(), event);
    }

    /**
     * Suppress all log-file output for the upcoming simulation. The log
     * file is opened lazily on the first non-silent event, so calling
     * this before any node is created (or {@code simulate} runs)
     * produces a fully silent run — no file at all. The setting is
     * cleared by {@link #shutdown}.
     */
    public void disableLogging() {
        EventLog.getInstance().setSilent(true);
    }

    /**
     * Re-enable log-file output if it was previously disabled via
     * {@link #disableLogging}. {@link #shutdown} also restores logging
     * for the next simulation.
     */
    public void enableLogging() {
        EventLog.getInstance().setSilent(false);
    }

    public static Simulator getInstance() {
        if (instance == null) {
            synchronized (Simulator.class) {
                if (instance == null) {
                    instance = new Simulator();
                }
            }
        }
        return instance;
    }

    /**
     * Runs the simulation for at most {@code durationInSeconds} seconds, or
     * until {@link #stop()} is called from anywhere — whichever comes first.
     * Returns once all node threads have terminated.
     */
    public void simulate ( long durationInSeconds ) {
        log(version + " - simulation started.");
        simulating = true;
        startSignal.countDown();
        try {
            // Block until the duration elapses or stop() fires the latch.
            //noinspection ResultOfMethodCallIgnored
            stopSignal.await(durationInSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        simulating = false;
        List<NetworkConnection> ncs = Network.getInstance().getAllNetworkConnections();
        for (NetworkConnection nc : ncs) {
            nc.interrupt();
        }
        for (NetworkConnection nc : ncs ) {
            nc.join();
        }
    }

    /**
     * Runs the simulation until every node terminates on its own — either
     * by returning from {@code engage()} naturally, or because something
     * outside the simulation called {@link #stop()}.
     */
    public void simulate () {
        log(version + " - simulation started.");
        simulating = true;
        startSignal.countDown();
        List<NetworkConnection> ncs = Network.getInstance().getAllNetworkConnections();
        for (NetworkConnection nc : ncs) {
            nc.join();
        }
    }

    /**
     * Requests an immediate end to the simulation. Must be called from
     * <em>outside</em> the simulation — the test thread, or an external
     * scheduler — never from inside an Actor's {@code engage()} loop. A
     * real distributed system cannot be stopped by one node; termination
     * must propagate via messages (token rings, Dijkstra-Scholten, leader
     * election) or come from an external trigger such as a timeout. This
     * method enforces that constraint by failing fast when invoked from a
     * registered node thread.
     *
     * <p>Effect: every node's {@link Node#receive() receive} returns
     * {@code null}, the timeout in {@link #simulate(long)} is short-
     * circuited, and the matching {@code simulate*} method returns once
     * all nodes have exited.
     *
     * @throws IllegalStateException if called from a node thread.
     */
    public void stop() {
        if (Network.getInstance().isCurrentThreadANode()) {
            throw new IllegalStateException(
                    "Simulator.stop() must not be called from inside a node — " +
                    "a distributed system cannot be stopped by one node. " +
                    "Use simulate(durationInSeconds) for a time-bounded run, " +
                    "or propagate termination through messages between nodes " +
                    "(see OneRingToRuleThemAllTest for a token-passing example).");
        }
        simulating = false;
        stopSignal.countDown();
        for (NetworkConnection nc : Network.getInstance().getAllNetworkConnections()) {
            nc.interrupt();
        }
    }

    /**
     * Resets the framework so a fresh {@code simulate} call can run. Clears
     * the node registry, replaces the start/stop latches, and clears the
     * configurable simulation behavior. Call this at the end of every
     * simulation — once per test method in a JUnit suite.
     */
    public void shutdown () {
        Network.getInstance().shutdown();
        SimulationBehavior.reset();
        startSignal = new CountDownLatch(1);
        stopSignal = new CountDownLatch(1);
        simulating = false;
        log(version + " - simulation ended.");
        simulatorSeq.set(0);
        EventLog.getInstance().setSilent(false);
    }

    public boolean isSimulating() {
        return simulating;
    }
    private static Simulator instance = null;
    private volatile boolean simulating = false;
    private CountDownLatch startSignal = new CountDownLatch(1);
    private CountDownLatch stopSignal = new CountDownLatch(1);

    public void awaitSimulationStart() {
        if (simulating) return;
        try {
            startSignal.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
