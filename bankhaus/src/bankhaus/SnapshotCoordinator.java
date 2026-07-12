package bankhaus;

import bankhaus.Messages.ChannelReport;
import bankhaus.Messages.NaiveReport;
import bankhaus.Messages.NaiveRequest;
import bankhaus.Messages.SnapshotRequest;
import bankhaus.Messages.StateReport;
import bankhaus.Messages.StopTransfers;
import bankhaus.Messages.Terminate;
import bankhaus.Results.NaiveStat;
import bankhaus.Results.RunResult;
import bankhaus.Results.SnapshotStat;
import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Der Koordinator des Einfaerbeverfahrens (Aufgabe 2, Variante (a)) und zugleich
 * der Treiber der Experimente (Aufgabe 3).
 *
 * <p>Ablauf eines Laufs:
 * <ol>
 *   <li>Vorlauf, damit Geld in den Kanaeln unterwegs ist.</li>
 *   <li>Je Runde: erst ein <em>naiver</em> Schnappschuss (nur Kontostaende), direkt
 *       danach ein <em>konsistenter</em> Schnappschuss (Konten + Kanaele). Beide laufen
 *       waehrend des Betriebs — die Prozesse ueberweisen ununterbrochen weiter.</li>
 *   <li>Ueberweisungen stoppen, Kanaele leerlaufen lassen, Simulation beenden.</li>
 * </ol>
 *
 * <h2>Wann ist ein Schnappschuss vollstaendig?</h2>
 * Nach n Zustandsmeldungen kennt der Koordinator fuer jeden Kanal i -> j die Anzahl
 * {@code sent_i[j]} der von i vor seinem Schnitt gesendeten und die Anzahl
 * {@code recv_j[i]} der von j vor seinem Schnitt empfangenen Ueberweisungen. Weil keine
 * Nachricht verlorengeht und weil ein weisser Prozess niemals eine schwarze Nachricht
 * verarbeitet (er faerbt sich vorher ein), ist jede von j vor seinem Schnitt empfangene
 * Nachricht auch von i vor dessen Schnitt gesendet worden. Also sind auf dem Kanal genau
 * {@code sent_i[j] - recv_j[i]} Ueberweisungen unterwegs. Genau so viele Nachmeldungen
 * ({@link ChannelReport}) muss der Koordinator abwarten — dann ist der Schnitt komplett.
 * Diese Abbruchbedingung braucht <em>keine</em> FIFO-Kanaele.
 */
public class SnapshotCoordinator extends Node {

    private final List<String> accounts;
    private final Config cfg;
    private final DelayedLinks links;
    private final Random rnd;

    private final List<SnapshotStat> snapshots = new ArrayList<>();
    private final List<NaiveStat> naiveSnapshots = new ArrayList<>();
    private volatile RunResult result;

    public SnapshotCoordinator(String name, List<String> accounts, Config cfg,
                               DelayedLinks links, long seed) {
        super(name);
        this.accounts = List.copyOf(accounts);
        this.cfg = cfg;
        this.links = links;
        this.rnd = new Random(seed);
    }

    /** Nach {@code simulator.simulate()} lesbar: der Knoten-Thread ist dann beendet. */
    public RunResult result() {
        return result;
    }

    @Override
    protected void engage() {
        try {
            sleep(cfg.warmupMs());
            for (int round = 1; round <= cfg.snapshotRounds(); round++) {
                if (!naiveSnapshot(round)) return;
                if (!consistentSnapshot(round)) return;
                sleep(cfg.betweenSnapshotsMs());
            }
        } catch (RuntimeException e) {
            System.err.println("Koordinator abgebrochen: " + e);
        } finally {
            result = new RunResult(cfg, List.copyOf(snapshots), List.copyOf(naiveSnapshots),
                    AccountNode.transfersSent());
            shutdownGroup();
        }
    }

    // ------------------------------------------------------------------
    // Konsistenter Schnappschuss: Koordinator-/Einfaerbeverfahren
    // ------------------------------------------------------------------

    /** @return false, wenn die Simulation waehrenddessen beendet wurde */
    private boolean consistentSnapshot(int color) {
        long t0 = System.nanoTime();

        // (state?, Farbe) per Multicast an die Gruppe.
        multicast(new SnapshotRequest(color));

        Map<String, StateReport> states = new LinkedHashMap<>();
        List<ChannelReport> channel = new ArrayList<>();
        int expected = -1;                    // -1 = noch unbekannt

        // Nachmeldungen koennen eintreffen, bevor die letzte Zustandsmeldung da ist —
        // deshalb eine gemeinsame Sammelschleife statt zweier Phasen.
        while (states.size() < cfg.n() || expected < 0 || channel.size() < expected) {
            ReceivedMessage rm = receive();
            if (rm == null) return false;
            switch (rm.message()) {
                case StateReport sr when sr.color() == color -> states.put(sr.node(), sr);
                case ChannelReport cr when cr.color() == color -> channel.add(cr);
                default -> throw new IllegalStateException(
                        "Unerwartete Nachricht in Runde " + color + ": " + rm.message());
            }
            if (states.size() == cfg.n() && expected < 0) {
                expected = totalInTransit(states);
            }
        }
        long durationMs = (System.nanoTime() - t0) / 1_000_000;
        verifyChannelCounts(states, channel);

        int balanceSum = states.values().stream().mapToInt(StateReport::balance).sum();
        int channelSum = channel.stream().mapToInt(ChannelReport::amount).sum();
        int controlMessages = 2 * cfg.n() + channel.size();

        SnapshotStat stat = new SnapshotStat(color, balanceSum, channelSum, channel.size(),
                controlMessages, durationMs);
        snapshots.add(stat);

        if (cfg.verbose()) {
            printGlobalState(color, states, channel, stat);
        }
        return true;
    }

    /** Summe der Kanalzustaende ueber alle gerichteten Kanaele, aus den Zaehlern der Meldungen. */
    private int totalInTransit(Map<String, StateReport> states) {
        int sum = 0;
        for (String from : accounts) {
            for (String to : accounts) {
                if (!from.equals(to)) sum += inTransit(states, from, to);
            }
        }
        return sum;
    }

    /** {@code sent_from[to] - recv_to[from]}: Anzahl der auf diesem Kanal unterwegs befindlichen Nachrichten. */
    private int inTransit(Map<String, StateReport> states, String from, String to) {
        int sent = states.get(from).sentCount().getOrDefault(to, 0);
        int received = states.get(to).receivedCount().getOrDefault(from, 0);
        int diff = sent - received;
        if (diff < 0) {
            throw new IllegalStateException("Kanal " + from + "->" + to + ": mehr empfangen als gesendet ("
                    + sent + "/" + received + ") — der Schnitt waere nicht konsistent.");
        }
        return diff;
    }

    /**
     * Gegenprobe: die aus den Zaehlern erwartete Kanalbelegung muss exakt den
     * eingegangenen Nachmeldungen entsprechen — pro Kanal, nicht nur in der Summe.
     */
    private void verifyChannelCounts(Map<String, StateReport> states, List<ChannelReport> channel) {
        for (String from : accounts) {
            for (String to : accounts) {
                if (from.equals(to)) continue;
                int expected = inTransit(states, from, to);
                long actual = channel.stream().filter(c -> c.from().equals(from) && c.to().equals(to)).count();
                if (expected != actual) {
                    throw new IllegalStateException("Kanal " + from + "->" + to + ": erwartet " + expected
                            + " unterwegs, nachgemeldet " + actual);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Naiver Schnappschuss (Aufgabe 3.2)
    // ------------------------------------------------------------------

    private boolean naiveSnapshot(int id) {
        multicast(new NaiveRequest(id));
        int sum = 0;
        int got = 0;
        while (got < cfg.n()) {
            ReceivedMessage rm = receive();
            if (rm == null) return false;
            if (rm.message() instanceof NaiveReport nr && nr.id() == id) {
                sum += nr.balance();
                got++;
            } else {
                throw new IllegalStateException("Unerwartete Nachricht im naiven Schnappschuss: " + rm.message());
            }
        }
        NaiveStat stat = new NaiveStat(id, sum, sum - cfg.totalMoney(), 2 * cfg.n());
        naiveSnapshots.add(stat);
        if (cfg.verbose()) {
            System.out.printf("%n--- Naiver Schnappschuss #%d (nur Konten, Kanaele ignoriert) ---%n", id);
            System.out.printf("  Summe der Kontostaende : %d%n", sum);
            System.out.printf("  Gesamtsumme S          : %d%n", cfg.totalMoney());
            System.out.printf("  Abweichung             : %+d  -> %s%n", stat.delta(),
                    stat.delta() == 0 ? "zufaellig korrekt"
                            : stat.delta() < 0 ? "Geld verschwunden (Ueberweisung unterwegs, nirgends erfasst)"
                            : "Geld entstanden (Empfang im Schnitt, Sendeereignis nicht)");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Ausgabe des vollstaendigen globalen Zustands
    // ------------------------------------------------------------------

    private void printGlobalState(int color, Map<String, StateReport> states,
                                  List<ChannelReport> channel, SnapshotStat stat) {
        System.out.printf("%n=== Konsistenter Schnappschuss (Runde/Farbe %d) ===%n", color);

        System.out.println("  Lokale Zustaende:");
        for (String a : accounts) {
            System.out.printf("    %-4s balance = %6d%n", a, states.get(a).balance());
        }

        System.out.println("  Kanalzustaende (unterwegs zum Schnittzeitpunkt):");
        Map<String, List<ChannelReport>> byChannel = new TreeMap<>();
        for (ChannelReport cr : channel) {
            byChannel.computeIfAbsent(cr.from() + " -> " + cr.to(), k -> new ArrayList<>()).add(cr);
        }
        if (byChannel.isEmpty()) {
            System.out.println("    (alle Kanaele leer)");
        }
        for (var e : byChannel.entrySet()) {
            StringBuilder sb = new StringBuilder();
            int sum = 0;
            for (ChannelReport cr : e.getValue()) {
                sb.append(sb.isEmpty() ? "" : ", ").append("Transfer#").append(cr.id()).append('(').append(cr.amount()).append(')');
                sum += cr.amount();
            }
            System.out.printf("    %-12s %s  [Summe %d]%n", e.getKey(), sb, sum);
        }

        int s = cfg.totalMoney();
        System.out.printf("  Summe Konten  : %d%n", stat.balanceSum());
        System.out.printf("  Summe Kanaele : %d (%d Ueberweisungen unterwegs)%n", stat.channelSum(), stat.inTransit());
        System.out.printf("  Gesamt        : %d   (S = %d)  -> %s%n", stat.total(), s,
                stat.consistent(s) ? "INVARIANTE ERFUELLT" : "VERLETZT");
        System.out.printf("  Kontrollnachrichten: %d (= 2n + %d Nachmeldungen), Dauer %d ms%n",
                stat.controlMessages(), stat.inTransit(), stat.durationMs());
    }

    // ------------------------------------------------------------------
    // Infrastruktur
    // ------------------------------------------------------------------

    private void shutdownGroup() {
        multicast(new StopTransfers());
        sleep(cfg.drainMs());
        // Ohne Verzoegerung, damit die Simulation auch dann endet, wenn der
        // Koordinator wegen eines Fehlers vorzeitig aussteigt.
        for (String a : accounts) send(new Terminate(), a);
    }

    /** Multicast an die Gruppe: n gerichtete Sendungen, jede mit eigener Latenz. */
    private void multicast(Message m) {
        for (String a : accounts) {
            links.after(randomLatency(), () -> send(m, a));
        }
    }

    private int randomLatency() {
        return cfg.minLatencyMs() + rnd.nextInt(cfg.maxLatencyMs() - cfg.minLatencyMs() + 1);
    }
}
