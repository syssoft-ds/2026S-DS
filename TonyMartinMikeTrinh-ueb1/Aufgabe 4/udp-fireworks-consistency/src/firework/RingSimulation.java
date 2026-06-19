package firework;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Aufgabe 4 – Konsistenz.
 *
 * <p>Erweiterung der Aufgabe-3-Simulation (Feuerwerk-Token-Ring in sim4da) um Mechanismen, die
 * <b>inkonsistente Sichten erkennen, melden und vermeiden</b>.
 *
 * <h2>Konsistenzkriterien dieser Anwendung</h2>
 * <ul>
 *   <li><b>K1 – Token-Konsistenz (gegenseitiger Ausschluss):</b> Es kreist genau <i>ein</i> Token;
 *       jeder Knoten sieht die Runden-Nummern (laps) <i>monoton steigend</i>. (Coulouris §15.2)</li>
 *   <li><b>K2 – Agreement über die Feuerwerke:</b> Am Ende kennt <i>jeder</i> Knoten dieselbe
 *       Anzahl gezündeter Raketen, und diese stimmt mit dem autoritativen {@code fwTotal} des
 *       Tokens überein. (Multicast-Agreement, Coulouris §15.4; Konsistenz Van Steen Kap. 7)</li>
 *   <li><b>K3 – Kausale Terminierung:</b> Das Abbruchsignal darf kein Feuerwerk „überholen": kein
 *       Knoten beendet sich, solange für ihn noch ein kausal früheres Feuerwerk aussteht.</li>
 * </ul>
 *
 * <h2>Mechanismen</h2>
 * <ul>
 *   <li><b>Logische Uhren (Lamport):</b> jede Nachricht trägt einen Zeitstempel; {@code lamport}
 *       wird bei jedem Ereignis/Empfang fortgeschrieben → kausaler Ordnungsrahmen. (Coulouris §14.4 /
 *       Van Steen §6.2)</li>
 *   <li><b>Erkennen &amp; Melden:</b> jeder Knoten prüft lokal K1 (Lap-Monotonie). Zur Terminierung
 *       sammelt eine <b>zweiphasige Terminierung</b> die je Knoten beobachteten Zählungen ein und
 *       vergleicht sie gegen {@code fwTotal} → eine {@code CONSISTENCY}-Verdict-Zeile.</li>
 *   <li><b>Vermeiden (zweiphasig):</b> Statt eines <i>racing</i> Stop-Broadcasts zirkuliert Knoten 0
 *       nach {@code k} stillen Runden zuerst ein {@code Finalize}-Token durch den <b>FIFO-Ring</b>.
 *       Dank FIFO hat jeder Knoten beim Empfang von {@code Finalize} bereits alle vorher zugestellten
 *       Feuerwerke verarbeitet (erfüllt K3); die Erhebung ist damit kausal konsistent. Erst danach
 *       broadcastet Knoten 0 {@code Stop}.</li>
 *   <li><b>Demonstration:</b> {@code --inject-loss p} verwirft FIRE-Beobachtungen mit
 *       Wahrscheinlichkeit {@code p} und reproduziert so das <i>UDP-Omission-Fehlermodell</i> aus
 *       Aufgabe 1/2 (Coulouris §4.2.1). Der Detektor meldet dann {@code verdict=INCONSISTENT}.</li>
 * </ul>
 */
public class RingSimulation {

    // --- Nachrichten (records); jede trägt einen Lamport-Zeitstempel ts ----------------------
    record Token(int lap, int fired, long fwTotal, long ts) implements Message {}
    record Fire(int rank, int lap, long ts) implements Message {}
    /** Sammel-Token der zweiphasigen Terminierung: trägt die je Knoten beobachteten Zählungen. */
    record Finalize(long fwTotal, Map<Integer, Integer> observed, long ts) implements Message {}
    record Stop(long ts) implements Message {}

    // =========================================================================================
    static class RingNode extends Node {

        private final int rank;
        private final int n;
        private final String successor;
        private final double p0;
        private final int k;
        private final double lossP;          // Fehlerinjektion: P(FIRE-Beobachtung verworfen)

        private double p;
        private long lamport = 0;            // Lamport-Uhr (nur eigener Thread → kein Sharing)
        private int fireworksSent = 0;
        private int fireworksObserved = 0;   // eigene + empfangene (== meine Sicht der Raketenzahl)

        // K1 – Token-Konsistenz
        private int lastLap = 0;
        private int lapViolations = 0;

        // nur Knoten 0 (Initiator)
        private final List<Long> roundDurationsNanos = new ArrayList<>();
        private int consecutiveQuietLaps = 0;
        private long fwTotal = 0;
        private int completedLaps = 0;

        RingNode(int rank, int n, double p0, int k, double lossP) {
            super(String.valueOf(rank));
            this.rank = rank;
            this.n = n;
            this.successor = String.valueOf((rank + 1) % n);
            this.p0 = p0;
            this.k = k;
            this.lossP = lossP;
            this.p = p0;
        }

        // --- Lamport-Uhr -------------------------------------------------------------------
        /** Lokales Ereignis / Sende-Ereignis: Uhr erhöhen und neuen Zeitstempel liefern. */
        private long tick() { return ++lamport; }
        /** Empfangs-Ereignis: Uhr auf max(eigene, empfangene)+1 setzen. */
        private void onReceive(long ts) { lamport = Math.max(lamport, ts) + 1; }

        @Override
        protected void engage() {
            if (rank == 0) runInitiator();
            else runFollower();
        }

        // --- Initiator (rank 0) ------------------------------------------------------------
        private void runInitiator() {
            long lapStart = System.nanoTime();
            send(doTurn(new Token(1, 0, 0, tick())), successor);

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Token rt -> {
                        onReceive(rt.ts());
                        roundDurationsNanos.add(System.nanoTime() - lapStart);
                        completedLaps = rt.lap();
                        fwTotal = rt.fwTotal();
                        checkMonotonic(rt.lap());

                        if (rt.fired() == 0) consecutiveQuietLaps++; else consecutiveQuietLaps = 0;
                        if (consecutiveQuietLaps >= k) {
                            // PHASE 1: Beobachtungen kausal über den FIFO-Ring einsammeln (erfüllt K3).
                            Map<Integer, Integer> seed = new HashMap<>();
                            seed.put(rank, fireworksObserved);
                            send(new Finalize(fwTotal, Map.copyOf(seed), tick()), successor);
                            // weiter in der Schleife: auf Rückkehr des Finalize warten
                        } else {
                            lapStart = System.nanoTime();
                            send(doTurn(new Token(rt.lap() + 1, 0, rt.fwTotal(), tick())), successor);
                        }
                    }
                    case Finalize fin -> {
                        onReceive(fin.ts());
                        // Finalize ist einmal umgelaufen → enthält die Beobachtungen aller n Knoten.
                        String verdict = verifyAndReport(fin);
                        broadcast(new Stop(tick()));      // PHASE 2: jetzt ist Beenden sicher
                        printSummary("ok", verdict);
                        return;
                    }
                    case Fire f -> { onReceive(f.ts()); observeFire(); }
                    case Stop s -> { onReceive(s.ts()); return; }
                    default -> { }
                }
            }
        }

        // --- Follower (rank != 0) ----------------------------------------------------------
        private void runFollower() {
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Token t -> { onReceive(t.ts()); checkMonotonic(t.lap()); send(doTurn(t), successor); }
                    case Fire f -> { onReceive(f.ts()); observeFire(); }
                    case Finalize fin -> {
                        onReceive(fin.ts());
                        // FIFO-Garantie: alle vor diesem Finalize zugestellten Fires sind schon gezählt.
                        Map<Integer, Integer> m = new HashMap<>(fin.observed());
                        m.put(rank, fireworksObserved);
                        send(new Finalize(fin.fwTotal(), Map.copyOf(m), tick()), successor);
                    }
                    case Stop s -> { onReceive(s.ts()); return; }
                    default -> { }
                }
            }
        }

        /** Zündet mit Wahrscheinlichkeit p, halbiert p, liefert das fortgeschriebene Token. */
        private Token doTurn(Token t) {
            int fired = t.fired();
            long fw = t.fwTotal();
            if (ThreadLocalRandom.current().nextDouble() < p) {
                broadcast(new Fire(rank, t.lap(), tick()));
                fireworksSent++;
                fireworksObserved++;          // das eigene Feuerwerk gehört zu meiner Sicht
                fired = 1;
                fw = t.fwTotal() + 1;
            }
            p = p / 2.0;
            return new Token(t.lap(), fired, fw, tick());
        }

        /** Beobachtet ein fremdes Feuerwerk – mit Fehlerinjektion (simuliertes UDP-Omission). */
        private void observeFire() {
            if (lossP > 0 && ThreadLocalRandom.current().nextDouble() < lossP) return; // „verloren"
            fireworksObserved++;
        }

        /** K1: Lap-Nummern müssen monoton steigen (ein Token, FIFO). Verletzung lokal melden. */
        private void checkMonotonic(int lap) {
            if (lap < lastLap) {
                lapViolations++;
                System.out.printf(Locale.US,
                        "INCONSISTENCY rank=%d type=lap_order saw=%d after=%d%n", rank, lap, lastLap);
            }
            lastLap = Math.max(lastLap, lap);
        }

        /** Nur Knoten 0: K2-Agreement prüfen und CONSISTENCY-Zeile ausgeben. */
        private String verifyAndReport(Finalize fin) {
            Map<Integer, Integer> obs = fin.observed();
            int F = (int) fin.fwTotal();
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, disagreeing = 0;
            StringBuilder detail = new StringBuilder();
            for (int r = 0; r < n; r++) {
                int v = obs.getOrDefault(r, -1);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
                if (v != F) {
                    disagreeing++;
                    if (detail.length() < 180) detail.append(r).append('=').append(v).append(',');
                }
            }
            String verdict = (disagreeing == 0) ? "ok" : "INCONSISTENT";
            System.out.printf(Locale.US,
                    "CONSISTENCY verdict=%s fwTotal=%d observed_min=%d observed_max=%d "
                    + "disagreeing=%d/%d lap_violations=%d lamport_max=%d loss=%.2f%n",
                    verdict, F, lo, hi, disagreeing, n, lapViolations, lamport, lossP);
            if (disagreeing > 0) {
                System.out.printf(Locale.US, "CONSISTENCY_DETAIL below_fwTotal=[%s]%n", detail.toString());
            }
            System.out.flush();
            return verdict;
        }

        /** Nur Knoten 0: SUMMARY-Zeile (identisch zu Aufgabe 1/3, plus mode=sim, consistency=<verdict>). */
        private void printSummary(String status, String verdict) {
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
            System.out.printf(Locale.US,
                    "SUMMARY n=%d status=%s rounds=%d multicasts=%d "
                    + "rt_min_ms=%.3f rt_mean_ms=%.3f rt_max_ms=%.3f p0=%s k=%d mode=sim consistency=%s%n",
                    n, status, completedLaps, fwTotal, min, mean, max, p0, k, verdict);
            System.out.flush();
        }
    }

    // =========================================================================================
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: RingSimulation <n> <p0> <k> [lossP]");
            System.exit(2);
        }
        int n        = Integer.parseInt(args[0]);
        double p0    = Double.parseDouble(args[1]);
        int k        = Integer.parseInt(args[2]);
        double lossP = (args.length > 3) ? Double.parseDouble(args[3]) : 0.0;

        Simulator simulator = Simulator.getInstance();
        simulator.disableLogging();

        for (int rank = 0; rank < n; rank++) {
            new RingNode(rank, n, p0, k, lossP);
        }

        simulator.simulate();
        simulator.shutdown();
    }
}
