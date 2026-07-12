package bankhaus;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class BankNode extends Node {

    protected final Config cfg;
    protected final int id;
    protected final Random rng;

    protected int balance;
    protected int round = 0;          // current color (0 = never colored)
    protected int transfersLeft;

    private final Map<Integer, Integer> sentByStamp = new HashMap<>();
    private final Map<Integer, Integer> recvByStamp = new HashMap<>();

    public BankNode(int id, Config cfg) {
        super(name(id));
        this.id = id;
        this.cfg = cfg;
        this.balance = cfg.startBalance();
        this.transfersLeft = cfg.transfersPerNode();
        this.rng = new Random(cfg.seed() * 31L + id);
    }

    protected static String name(int id) {
        return "P" + id;
    }

    @Override
    protected void engage() {
        for (int i = 0; i < cfg.seedTransfers(); i++) initiateTransfer();

        while (true) {
            ReceivedMessage rm = receive();
            if (rm == null) return; // simulation ended
            switch (rm.message()) {
                case Messages.Transfer t        -> onTransfer(t, rm.sender());
                case Messages.StateRequest sr   -> { if (sr.round() > round) takeLocalSnapshot(sr.round()); }
                case Messages.BalanceRequest br -> send(new Messages.BalanceReply(id, balance), name(0));
                // handled only by CoordinatorNode (overridden hooks):
                case Messages.StateReport sr    -> onStateReport(sr);
                case Messages.ChannelReport cr  -> onChannelReport(cr);
                case Messages.BalanceReply br   -> onBalanceReply(br);
                default -> throw new IllegalStateException("unexpected message: " + rm.message());
            }
        }
    }

    /** Debit immediately, stamp with current round, hold for the latency, then send. */
    protected void initiateTransfer() {
        if (transfersLeft <= 0 || balance <= 0) return;
        transfersLeft--;

        int target;
        do { target = rng.nextInt(cfg.n()); } while (target == id);
        int amount = 1 + rng.nextInt(Math.min(balance, cfg.maxTransfer()));

        balance -= amount;
        int stamp = round;
        sentByStamp.merge(stamp, 1, Integer::sum);

        sleep(cfg.minLatencyMs() + rng.nextInt(cfg.maxLatencyMs() - cfg.minLatencyMs() + 1));
        send(new Messages.Transfer(amount, stamp), name(target));
    }

    protected void onTransfer(Messages.Transfer t, String sender) {
        recvByStamp.merge(t.stamp(), 1, Integer::sum);

        if (t.stamp() > round) {
            // message from the future: recolor before processing
            takeLocalSnapshot(t.stamp());
        } else if (t.stamp() < round) {
            // white message at black process: channel state, forward a copy
            int from = Integer.parseInt(sender.substring(1));
            deliverToCoordinator(new Messages.ChannelReport(round, from, id, t.amount()));
        }

        balance += t.amount();
        afterTransferProcessed();
        initiateTransfer();
    }

    /** Recolor to newRound, save state, report it with the deficit counters of round-1. */
    protected void takeLocalSnapshot(int newRound) {
        round = newRound;
        int prev = newRound - 1;
        deliverToCoordinator(new Messages.StateReport(newRound, id, balance,
                sentByStamp.getOrDefault(prev, 0),
                recvByStamp.getOrDefault(prev, 0)));
    }

    /** Ordinary processes send over the network; the coordinator books locally. */
    protected void deliverToCoordinator(Message m) {
        send(m, name(0));
    }

    // Hooks filled in by CoordinatorNode
    protected void afterTransferProcessed() {}
    protected void onStateReport(Messages.StateReport sr) {}
    protected void onChannelReport(Messages.ChannelReport cr) {}
    protected void onBalanceReply(Messages.BalanceReply br) {}
}
