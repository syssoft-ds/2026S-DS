package org.oxoo2a.sim4da;

/**
 * Represents a message that has been received by a node, including the sender's identifier.
 * This class is used to encapsulate the details of a message that has been successfully received.
 */
public record ReceivedMessage (
        Message message,
        String sender ) {}