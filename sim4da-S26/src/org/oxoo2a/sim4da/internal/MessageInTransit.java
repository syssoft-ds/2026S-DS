package org.oxoo2a.sim4da.internal;

import org.oxoo2a.sim4da.Message;

/**
 * A message together with the name of the node that sent it. Carried by the
 * {@link Network} from sender to receiver mailbox.
 *
 * <p>No defensive copy is performed — {@link Message} implementations are
 * expected to be immutable (use records).
 */
public record MessageInTransit(Message message, String sender) {}
