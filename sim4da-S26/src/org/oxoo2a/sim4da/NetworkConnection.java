package org.oxoo2a.sim4da;

import org.oxoo2a.sim4da.internal.EventLog;
import org.oxoo2a.sim4da.internal.MessageInTransit;
import org.oxoo2a.sim4da.internal.Network;
import org.oxoo2a.sim4da.internal.NodeProxy;

/**
 * The user-facing handle into the simulation as the running algorithm
 * sees it (the HAS-A pattern). Surfaces every per-node capability that
 * {@link NodeProxy} concentrates from the simulation core, plus a small
 * set of connection-local concerns (the thread lifecycle, the node's
 * name, recording events to the log).
 *
 * <p>{@link Node} is a thin facade over this class — every method on
 * {@code Node} is a protected delegate to a corresponding public method
 * here. The discipline:
 *
 * <ol>
 *   <li>A new simulation-core capability lands first on {@link NodeProxy}.</li>
 *   <li>It is then surfaced here as a public method.</li>
 *   <li>It is mirrored on {@link Node} as a protected delegate.</li>
 * </ol>
 *
 * That keeps the IS-A and HAS-A patterns at parity, so the choice
 * between them is purely stylistic — it never changes what the algorithm
 * can do.
 */
public class NetworkConnection {


    /**
     * Initializes the network connection for a node.
     *
     * @param nodeName the unique identifier of the node.
     */
    public NetworkConnection(String nodeName ) {
        this.nodeName = nodeName;
        peer = new NodeProxy(nodeName);
        network.registerConnection(this,peer);
    }

    /**
     * Retrieves the name of this node's connection.
     *
     * @return the node name.
     */
    public String nodeName () {
        return nodeName;
    }

    /**
     * Starts the node's main thread after simulation start.
     *
     * @param nodeMain the Runnable containing node logic to execute.
     */
    public void engage ( Runnable nodeMain ) {
        this.nodeMain = nodeMain;
        thread = Thread.ofVirtual().name(nodeName).start(this::nodeMainBase);
    }

    private void nodeMainBase() {
        simulator.awaitSimulationStart();
        if (simulator.isSimulating())
            nodeMain.run();
    }

    /**
     * Waits for the node's main thread to finish execution.
     */
    public void join () {
        try {
            thread.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Receives the next message from the network. Blocks until a message
     * is available or the simulation is shut down.
     *
     * @return the next message, or {@code null} if the simulation has been
     *         shut down (the thread's interrupt flag is restored before
     *         returning, so subsequent blocking calls will exit promptly).
     */
    public ReceivedMessage receive () {
        try {
            MessageInTransit mit = network.receive(this);
            log("received from " + mit.sender());
            return new ReceivedMessage(mit.message(), mit.sender());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Sends a message to a specific node. If no node is registered under
     * {@code toNodeName}, the send is silently dropped — keeping
     * algorithm code free of try/catch ceremony. Use {@link #sendChecked}
     * when unknown recipients should surface as an exception.
     *
     * @param message the Message to send.
     * @param toNodeName the recipient node's name.
     */
    public void send ( Message message, String toNodeName ) {
        try {
            sendChecked(message, toNodeName);
        }
        catch (UnknownNodeException e) {
            // intentionally swallowed: see sendChecked for the strict variant
        }
    }

    /**
     * Sends a message to a specific node, throwing if the recipient is
     * unknown. The strict counterpart to {@link #send(Message, String)}.
     *
     * @param message the Message to send.
     * @param toNodeName the recipient node's name.
     * @throws UnknownNodeException if the target node is not registered.
     */
    public void sendChecked ( Message message, String toNodeName ) throws UnknownNodeException {
        log("sending to " + toNodeName);
        network.send(message, this, toNodeName);
    }

    /**
     * Broadcasts a message to all other nodes in the network. There is no
     * checked counterpart — broadcast has no specific recipient that could
     * be unknown.
     *
     * @param message the Message to broadcast.
     */
    public void broadcast ( Message message ) {
        log("broadcasting");
        network.send(message, this);
    }

    /**
     * Pauses execution for the given duration. Restores the thread's
     * interrupt flag if interrupted (typically by simulator shutdown), so
     * subsequent blocking calls exit promptly.
     *
     * @param millis the time to sleep in milliseconds.
     */
    public void sleep ( int millis ) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records one event in this node's local timeline. The line is
     * tagged with {@code [<nodeName>,<seq>]} where {@code seq} is a
     * monotonically increasing per-node counter — calls from one node
     * appear in the order they were made. Cross-node order in the log
     * file is left unspecified; causality between nodes is something
     * the algorithm establishes via messages.
     */
    public void log(String event) {
        peer.log(event);
    }

    private final String nodeName;
    private final Simulator simulator = Simulator.getInstance();
    private final Network network = Network.getInstance();
    private Thread thread = null;
    private final NodeProxy peer;
    private Runnable nodeMain = null;

    /**
     * Interrupts the node's main execution thread.
     */
    public void interrupt() {
        thread.interrupt();
    }
}
