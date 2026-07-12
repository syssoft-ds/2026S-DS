package org.oxoo2a.sim4da;

/**
 * The physical-machine abstraction in a sim4da simulation. Subclasses
 * personalize a node by overriding {@link #engage} with whatever algorithm
 * they want that node to run.
 *
 * <p>Each node runs on its own virtual thread and exchanges messages with
 * other nodes via {@link #send}, {@link #broadcast}, and {@link #receive}.
 * That is all the framework commits to — it deliberately does <em>not</em>
 * commit to the Actor model. A node's {@code engage} body is free to:
 *
 * <ul>
 *   <li>spawn helper threads,</li>
 *   <li>hold non-trivial state across messages,</li>
 *   <li>follow synchronous-round, quorum-based, gossip, or any other
 *       discipline besides "process one message at a time".</li>
 * </ul>
 *
 * <p>If extending {@code Node} does not fit your design — for instance,
 * because your algorithmic class already has a parent — use the HAS-A
 * pattern instead: own a {@link NetworkConnection} directly and engage it
 * with a {@link Runnable}. See {@code NetworkConnection}'s class Javadoc
 * for the layering discipline that keeps the two patterns at parity.
 */
public class Node {

    /**
     * Constructs a Node with the given name, initializing its network connection.
     *
     * @param name the unique identifier for this node in the network.
     */
    public Node ( String name ) {
        nc = new NetworkConnection(name);
        nc.engage(this::engage);
    }

    /**
     * Called when the network connection engages this node.
     * Default implementation logs a no-op marker; override to define
     * custom behavior.
     */
    protected void engage () {
        nc.log("engaged, but no algorithm defined");
    }

    /**
     * Sends a message to a specific node. If no node is registered under
     * {@code toNodeName}, the send is silently dropped — keeping the
     * algorithm code in {@link #engage} free of try/catch ceremony.
     * Use {@link #sendChecked} when you want unknown recipients to be
     * surfaced as an exception.
     *
     * @param message the Message object to send.
     * @param toNodeName the name of the recipient node.
     */
    protected void send ( Message message, String toNodeName ) {
        nc.send(message, toNodeName);
    }

    /**
     * Sends a message to a specific node, throwing if the recipient is
     * unknown. The strict counterpart to {@link #send}; use this when the
     * algorithm needs to react to a missing recipient (e.g. to retry or to
     * log).
     *
     * @param message the Message object to send.
     * @param toNodeName the name of the recipient node.
     * @throws UnknownNodeException if the destination node is not registered.
     */
    protected void sendChecked ( Message message, String toNodeName ) throws UnknownNodeException {
        nc.sendChecked(message, toNodeName);
    }


    /**
     * Broadcasts a message to all other nodes in the network.
     *
     * @param message the Message object to broadcast.
     */
    protected void broadcast ( Message message ) {
        nc.broadcast(message);
    }

    /**
     * Receives the next message from the network. Blocks until a message
     * is available or the simulation is shut down.
     *
     * @return the next message, or {@code null} if the simulation has been
     *         shut down.
     */
    protected ReceivedMessage receive () {
        return nc.receive();
    }

    /**
     * Retrieves this node's unique name.
     *
     * @return the name of this node.
     */
    protected String nodeName () {
        return nc.nodeName();
    }

    /**
     * Pauses execution for the specified duration. Restores the interrupt
     * flag if interrupted by simulator shutdown.
     *
     * @param millis the time to sleep in milliseconds.
     */
    protected void sleep ( int millis ) {
        nc.sleep(millis);
    }

    /**
     * Records one event to the framework's log file. See
     * {@link NetworkConnection#log}.
     */
    protected void log ( String event ) {
        nc.log(event);
    }
    private NetworkConnection nc = null;
}
