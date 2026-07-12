package bankhaus;

import org.oxoo2a.sim4da.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P0: ordinary bank process <em>and</em> coordinator for both snapshot
 * variants.
 *
 * <p>Colored round r: save own state, broadcast StateRequest(r), collect
 * n StateReports and exactly D = sum(sentPrev) - sum(recvPrev)
 * ChannelReports (deficit counting — correct without FIFO), then print the
 * full global state and the consistency verdict.
 *
 * <p>Naive snapshot (Aufgabe 3): only asks for current balances and
 * ignores the channels — demonstrably inconsistent while transfers run.
 */
public class CoordinatorNode extends BankNode {

    private final Stats stats;

    private int processedSinceSnapshot = 0;
    private int snapshotNo = 0;

    // state of the running colored round
    private boolean coloredActive = false;
    private final Map<Integer, Integer> accounts = new TreeMap<>();
    private final Map<String, List<Integer>> channels = new TreeMap<>();
    private int reports, channelMsgs, ctrlMsgs, expSent, expRecv;

    // state of the running naive snapshot
    private boolean naiveActive = false;
    private final Map<Integer, Integer> naiveBalances = new TreeMap<>();
    private int naiveCtrl;

    public CoordinatorNode(Config cfg, Stats stats) {
        super(0, cfg);
        this.stats = stats;
    }

    /** Trigger: after snapshotEvery transfers processed by P0 itself. */
    @Override
    protected void afterTransferProcessed() {
        processedSinceSnapshot++;
        if (processedSinceSnapshot < cfg.snapshotEvery() || coloredActive || naiveActive) return;
        processedSinceSnapshot = 0;
        boolean colored = switch (cfg.mode()) {
            case COLORED     -> true;
            case NAIVE       -> false;
            case ALTERNATING -> snapshotNo % 2 == 0;
        };
        snapshotNo++;
        if (colored) startColoredSnapshot(); else startNaiveSnapshot();
    }

    private void startColoredSnapshot() {
        coloredActive = true;
        accounts.clear();
        channels.clear();
        reports = 0; channelMsgs = 0; ctrlMsgs = 0; expSent = 0; expRecv = 0;

        int newRound = round + 1;
        System.out.printf("%n=== [Runde %d] Einfaerbe-Schnappschuss gestartet ===%n", newRound);
        takeLocalSnapshot(newRound);                    // save own state first
        broadcast(new Messages.StateRequest(newRound)); // then "state?" to everyone else
        ctrlMsgs += cfg.n() - 1;
    }

    /** The coordinator books its own reports locally instead of sending to itself. */
    @Override
    protected void deliverToCoordinator(Message m) {
        switch (m) {
            case Messages.StateReport sr   -> onStateReport(sr);
            case Messages.ChannelReport cr -> onChannelReport(cr);
            default -> throw new IllegalStateException("unexpected local report: " + m);
        }
    }

    @Override
    protected void onStateReport(Messages.StateReport sr) {
        if (!coloredActive || sr.round() != round) return; // stale
        if (sr.nodeId() != id) ctrlMsgs++;
        reports++;
        accounts.put(sr.nodeId(), sr.balance());
        expSent += sr.sentPrev();
        expRecv += sr.recvPrev();
        checkColoredCompletion();
    }

    @Override
    protected void onChannelReport(Messages.ChannelReport cr) {
        if (!coloredActive || cr.round() != round) return; // stale
        if (cr.to() != id) ctrlMsgs++;
        channelMsgs++;
        channels.computeIfAbsent("P" + cr.from() + "->P" + cr.to(), k -> new ArrayList<>())
                .add(cr.amount());
        checkColoredCompletion();
    }

    /** Complete once all n reports and exactly D channel reports are in. */
    private void checkColoredCompletion() {
        if (reports < cfg.n()) return;
        int deficit = expSent - expRecv;
        if (channelMsgs < deficit) return;
        coloredActive = false;
        printGlobalState(deficit);
    }

    private void printGlobalState(int deficit) {
        long sumAccounts = accounts.values().stream().mapToLong(Integer::longValue).sum();
        long sumChannels = channels.values().stream()
                .flatMap(List::stream).mapToLong(Integer::longValue).sum();
        long total = sumAccounts + sumChannels;
        boolean consistent = total == cfg.totalSum();

        System.out.printf("=== [Runde %d] Globaler Zustand (Einfaerbeverfahren) ===%n", round);
        accounts.forEach((nid, bal) -> System.out.printf("  Konto P%-3d = %d%n", nid, bal));
        if (channels.isEmpty()) {
            System.out.println("  Kanaele: leer");
        } else {
            channels.forEach((chan, amounts) ->
                    System.out.printf("  Kanal %-10s : %s%n", chan, amounts));
        }
        System.out.printf("  Summe Konten = %d, Summe Kanaele = %d (Defizit %d)%n",
                sumAccounts, sumChannels, deficit);
        System.out.printf("  Erfasst = %d, Soll S = %d  ->  %s%n",
                total, cfg.totalSum(), consistent ? "KONSISTENT" : "INKONSISTENT");
        System.out.printf("  Kontrollnachrichten = %d (2(n-1) + E = %d + %d)%n",
                ctrlMsgs, 2 * (cfg.n() - 1), channelMsgs);

        stats.record("colored", cfg.n(), round, ctrlMsgs, channelMsgs,
                total, cfg.totalSum(), consistent);
    }

    private void startNaiveSnapshot() {
        naiveActive = true;
        naiveBalances.clear();
        naiveCtrl = 0;
        System.out.printf("%n=== Naiver Schnappschuss #%d (nur Kontostaende) ===%n", snapshotNo);
        naiveBalances.put(id, balance);
        broadcast(new Messages.BalanceRequest());
        naiveCtrl += cfg.n() - 1;
    }

    @Override
    protected void onBalanceReply(Messages.BalanceReply br) {
        if (!naiveActive) return; // stale
        naiveCtrl++;
        naiveBalances.put(br.nodeId(), br.balance());
        if (naiveBalances.size() < cfg.n()) return;

        naiveActive = false;
        long total = naiveBalances.values().stream().mapToLong(Integer::longValue).sum();
        boolean consistent = total == cfg.totalSum();
        naiveBalances.forEach((nid, bal) -> System.out.printf("  Konto P%-3d = %d%n", nid, bal));
        System.out.printf("  Summe = %d, Soll S = %d  ->  %s%n",
                total, cfg.totalSum(),
                consistent ? "zufaellig konsistent"
                           : "INKONSISTENT: Geld " + (total < cfg.totalSum() ? "verschwunden" : "entstanden"));

        stats.record("naive", cfg.n(), snapshotNo, naiveCtrl, 0,
                total, cfg.totalSum(), consistent);
    }
}
