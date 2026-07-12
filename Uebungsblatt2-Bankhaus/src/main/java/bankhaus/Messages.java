package bankhaus;

import org.oxoo2a.sim4da.Message;

/**
 * All messages as immutable records implementing the sim4da marker
 * interface {@link Message}. The base message is {@link Transfer}; its
 * {@code stamp} carries the sender's color (round counter) at debit time,
 * so cut membership is decided by the stamp alone — independent of the
 * (non-FIFO) delivery order.
 */
public final class Messages {

    private Messages() {}

    /** Base message: transfer of {@code amount}, stamped with the sender's round. */
    public record Transfer(int amount, int stamp) implements Message {}

    /** Coordinator -> all: "state?, color" — recolor to {@code round} and report. */
    public record StateRequest(int round) implements Message {}

    /** Process -> coordinator: saved balance plus deficit counters for stamp round-1. */
    public record StateReport(int round, int nodeId, int balance,
                              int sentPrev, int recvPrev) implements Message {}

    /** Process -> coordinator: a white transfer received by a black process (channel state). */
    public record ChannelReport(int round, int from, int to, int amount) implements Message {}

    /** Coordinator -> all: naive query for the current balance (comparison snapshot). */
    public record BalanceRequest() implements Message {}

    /** Process -> coordinator: reply to the naive query. */
    public record BalanceReply(int nodeId, int balance) implements Message {}
}
