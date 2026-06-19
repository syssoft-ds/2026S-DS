package firework;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Aufgabe 3 – Ein simuliertes Feuerwerk.
 *
 * <p>Dieselbe verteilte Anwendung wie Aufgabe 1 (logischer Ring, in dem ein Token /
 * "Streichholz" kreist; wer es hält, zündet mit Wahrscheinlichkeit p eine Rakete als
 * Broadcast und halbiert p; Terminierung nach k stillen Runden) – jetzt aber nicht mehr
 * mit echten UDP-Sockets, sondern im <b>In-Process-Simulator sim4da</b>.
 *
 * <p>Ausgangspunkt ist der Test {@code OneRingToRuleThemAll} aus
 * {@code github.com/syssoft-ds/sim4da-S26} (record-Nachrichten, {@code RingSegment extends Node},
 * Nachfolger {@code (i+1) % n}, Lifecycle {@code simulate()/shutdown()}).
 *
 * <p>Wesentliche Unterschiede zu Aufgabe 1/2 (für den Vergleich im Bericht):
 * <ul>
 *   <li><b>Zuverlässige Kanäle:</b> sim4da-Mailboxen puffern und verlieren nichts → kein
 *       Token-Verlust, kein {@code stalled}; auch kein READY/GO-Startbarrier nötig
 *       (Coulouris §4.2.1 / Van Steen §4.2 zum UDP-Fehlermodell, das hier entfällt).</li>
 *   <li><b>Ein Thread pro Knoten</b> statt eines Prozesses + Listener-Thread; geteilter Zustand
 *       entfällt fast vollständig (kein {@code volatile}/{@code Atomic} nötig).</li>
 *   <li><b>Nativer {@link Node#broadcast(Message)}</b> für die Feuerwerke statt UDP-Multicast /
 *       n−1 Unicasts (Coulouris §4.4.1 & §15.4 / Van Steen §4.4).</li>
 *   <li><b>Reale Zeit:</b> Rundenzeiten via {@link System#nanoTime()} messen die echte Wall-Clock
 *       des Thread-/Mailbox-Passings (kein {@code sleep()} im Ring, das Simulationszeit wäre).</li>
 * </ul>
 *
 * <p>Literatur: Ring-/Token-Koordination Coulouris §15.2; reale vs. simulierte Zeit Coulouris §2.4.
 */
public class RingSimulation {

    // --- Nachrichten: in sim4da ist Message ein Marker-Interface → Java-records ---------------

    /** Das kreisende Token. {@code fired} ist 0/1 (OR-Akkumulation pro Runde),
     *  {@code fwTotal} die kumulierte Raketenzahl über den ganzen Lauf. */
    record Token(int lap, int fired, long fwTotal) implements Message {}

    /** Eine gezündete Rakete (Broadcast an alle anderen Knoten). */
    record Fire(int rank, int lap) implements Message {}

    /** Abbruchsignal von Knoten 0 an alle anderen. */
    record Stop() implements Message {}

    // =========================================================================================
    //  Ein Ring-Knoten
    // =========================================================================================
    static class RingNode extends Node {

        // --- Konfiguration (immutable nach Konstruktion) ---
        private final int rank;
        private final int n;
        private final String successor;   // Knotenname des Nachfolgers: (rank+1) % n
        private final double p0;
        private final int k;

        // --- nur vom eigenen engage()-Thread berührt → keine Synchronisierung nötig ---
        private double p;                 // aktuelle Zündwahrscheinlichkeit
        private int fireworksSent = 0;    // lokal gezündete Raketen
        private int fireworksObserved = 0;// empfangene FIRE-Broadcasts (Cross-Check)

        // --- nur Knoten 0 (Initiator) ---
        private final List<Long> roundDurationsNanos = new ArrayList<>();
        private int consecutiveQuietLaps = 0;
        private long fwTotal = 0;          // kumulierte Raketen (aus zurückkommendem Token)
        private int completedLaps = 0;

        RingNode(int rank, int n, double p0, int k) {
            super(String.valueOf(rank));            // Knotenname = Rang ("0".."n-1")
            this.rank = rank;
            this.n = n;
            this.successor = String.valueOf((rank + 1) % n);
            this.p0 = p0;
            this.k = k;
            this.p = p0;
        }

        @Override
        protected void engage() {
            if (rank == 0) runInitiator();
            else runFollower();
        }

        // --- Initiator (rank 0): injiziert Token, misst Rundenzeiten, terminiert ---------------
        private void runInitiator() {
            // Erste Runde starten: eigener Zug, dann Token an den Nachfolger.
            long lapStart = System.nanoTime();
            send(doTurn(new Token(1, 0, 0)), successor);

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;                 // Simulation beendet
                switch (rm.message()) {
                    case Token rt -> {
                        // Rundenende: das Token kam von Rang n-1 zurück.
                        roundDurationsNanos.add(System.nanoTime() - lapStart);
                        completedLaps = rt.lap();
                        fwTotal = rt.fwTotal();

                        if (rt.fired() == 0) consecutiveQuietLaps++; else consecutiveQuietLaps = 0;
                        if (consecutiveQuietLaps >= k) {
                            broadcast(new Stop());      // alle anderen Knoten beenden lassen
                            printSummary("ok");
                            return;
                        }

                        // Nächste Runde: fired zurücksetzen, fwTotal kumulativ behalten.
                        lapStart = System.nanoTime();
                        send(doTurn(new Token(rt.lap() + 1, 0, rt.fwTotal())), successor);
                    }
                    case Fire f -> fireworksObserved++;
                    case Stop s -> { return; }          // sollte den Initiator nicht erreichen
                    default -> { /* unbekannte Nachricht ignorieren */ }
                }
            }
        }

        // --- Follower (rank != 0): Token weiterreichen, Feuerwerke zählen, bei Stop enden ------
        private void runFollower() {
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Token t -> send(doTurn(t), successor);
                    case Fire f  -> fireworksObserved++;
                    case Stop s  -> { return; }
                    default      -> { /* ignorieren */ }
                }
            }
        }

        /**
         * Zündet mit Wahrscheinlichkeit p, halbiert p und liefert das (immutable) Token mit
         * aktualisierten Feldern zurück.
         */
        private Token doTurn(Token t) {
            int fired = t.fired();
            long fw = t.fwTotal();
            if (ThreadLocalRandom.current().nextDouble() < p) {
                broadcast(new Fire(rank, t.lap()));     // Feuerwerk an alle anderen Knoten
                fireworksSent++;
                fired = 1;                              // OR-Akkumulation: bleibt für die Runde gesetzt
                fw = t.fwTotal() + 1;                   // kumulierte Raketen über den ganzen Lauf
            }
            p = p / 2.0;                                // Zündwahrscheinlichkeit pro Durchlauf reduzieren
            return new Token(t.lap(), fired, fw);
        }

        /** Nur Knoten 0: SUMMARY-Zeile – identisch zu Aufgabe 1, plus {@code mode=sim}. */
        private void printSummary(String status) {
            double min = 0, mean = 0, max = 0;
            if (!roundDurationsNanos.isEmpty()) {
                long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, sum = 0;
                for (long d : roundDurationsNanos) {
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                    sum += d;
                }
                min  = lo / 1_000_000.0;
                max  = hi / 1_000_000.0;
                mean = (sum / (double) roundDurationsNanos.size()) / 1_000_000.0;
            }
            // Locale.US erzwingt Dezimalpunkt → CSV-Parsing im Python-Script bleibt stabil.
            System.out.printf(Locale.US,
                    "SUMMARY n=%d status=%s rounds=%d multicasts=%d "
                    + "rt_min_ms=%.3f rt_mean_ms=%.3f rt_max_ms=%.3f p0=%s k=%d mode=sim%n",
                    n, status, completedLaps, fwTotal, min, mean, max, p0, k);
            System.out.flush();
        }
    }

    // =========================================================================================
    //  Aufbau & Start der Simulation
    // =========================================================================================
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: RingSimulation <n> <p0> <k>");
            System.exit(2);
        }
        int n     = Integer.parseInt(args[0]);
        double p0 = Double.parseDouble(args[1]);
        int k     = Integer.parseInt(args[2]);

        Simulator simulator = Simulator.getInstance();
        simulator.disableLogging();   // Ereignis-Log aus → weniger Overhead bei großem n; SUMMARY via stdout

        // Ring verdrahten: Knoten 0..n-1, Nachfolger jeweils (i+1) % n.
        for (int rank = 0; rank < n; rank++) {
            new RingNode(rank, n, p0, k);
        }

        simulator.simulate();         // läuft, bis alle engage() zurückkehren (Terminierung per Stop)
        simulator.shutdown();
    }
}
