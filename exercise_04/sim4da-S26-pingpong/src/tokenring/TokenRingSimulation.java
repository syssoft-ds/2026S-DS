package tokenring;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.*;

/**
 * Token-Ring-Simulation mit sim4da.
 *
 * n Knoten bilden einen logischen Ring und lassen ein Token umlaufen.
 * Jeder Knoten, der das Token erhält, "zündet" mit Wahrscheinlichkeit p
 * eine Rakete (Broadcast an alle anderen Knoten) und halbiert danach sein p.
 * Nach k aufeinanderfolgenden Runden ohne Rakete terminiert die Simulation.
 *
 * Aufruf: ./gradlew runTokenRing --args="<n> [<p0> [<k>]]"
 * n   – Ringgröße (Anzahl Knoten)
 * p0  – Anfangszündwahrscheinlichkeit (Standard: 0.5)
 * k   – aufeinanderfolgende leere Runden bis Terminierung (Standard: 3)
 *
 * Ausgabe (letzte Zeile stdout):
 * STATS n=… rounds=… broadcasts=… min_rt_ms=… mean_rt_ms=… max_rt_ms=…
 */
public class TokenRingSimulation {

    // ── Nachrichten ──────────────────────────────────────────────────────────

    /** Wandert im Ring: zählt Raketen der aktuellen Runde mit. */
    record Token(int round, int fireworks, Set<String> originators) implements Message {}

    /** Broadcast: ein Knoten hat eine Rakete gezündet. */
    record Firework(int round, String from) implements Message {}

    /** Terminierungssignal: alle Knoten sollen beenden. */
    record Shutdown() implements Message {}

    // ── Regulärer Knoten ─────────────────────────────────────────────────────

    static class RingNode extends Node {
        final int index;
        final String nextNode;
        final List<String> allNodes;
        double p;
        // Speicher für empfangene Feuerwerke der AKTUELLEN Runde
        final Set<String> receivedInCurrentRound = new HashSet<>();

        RingNode(int index, List<String> allNodes, double p0) {
            super("Node-" + index);
            this.index = index;
            this.nextNode = allNodes.get((index + 1) % allNodes.size());
            this.allNodes = allNodes;
            this.p = p0;
        }

        Token passToken(Token t) {
            int fw = t.fireworks();
            Set<String> originators = new HashSet<>(t.originators());

            if (Math.random() < p) {
                for (String node : allNodes) {
                    if (!node.equals(nodeName())) {
                        send(new Firework(t.round(), nodeName()), node); // Name mitgeben
                    }
                }
                fw++;
                originators.add(nodeName()); // Uns selbst eintragen
            }
            p /= 2;
            return new Token(t.round(), fw, originators);
        }

        @Override
        protected void engage() {
            int lastProcessedRound = -1;
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Shutdown s -> { send(s, nextNode); return; }
                    case Firework(int round, String from) -> {
                        receivedInCurrentRound.add(from);
                    }
                    case Token t -> {
                        // Bei neuer Runde den lokalen Speicher leeren
                        if (t.round() > lastProcessedRound) {
                            receivedInCurrentRound.clear();
                            lastProcessedRound = t.round();
                        }

                        // ERKENNUNGS-MECHANISMUS:
                        // Prüfen, ob uns Raketen fehlen, die laut Token existieren sollten
                        Set<String> expected = t.originators();
                        if (!receivedInCurrentRound.containsAll(expected)) {
                            System.err.printf("[INKONSISTENZ] %s: Token meldet Raketen von %s, aber real empfangen nur von %s%n",
                                    nodeName(), expected, receivedInCurrentRound);
                        }

                        Token updated = passToken(t);
                        send(updated, nextNode);
                    }
                    default -> throw new IllegalStateException("Unerwartet: " + rm.message());
                }
            }
        }
    }

    // ── Initiator (Knoten 0) ─────────────────────────────────────────────────

    static class Initiator extends RingNode {
        private final int k;

        // Nach simulate() lesbare Statistik
        long rounds;
        long broadcasts;
        long minNs = Long.MAX_VALUE;
        long maxNs = 0;
        double meanNs;

        Initiator(List<String> allNodes, int k, double p0) {
            super(0, allNodes, p0);
            this.k = k;
        }

        @Override
        protected void engage() {
            List<Long> times = new ArrayList<>();
            long totalFw = 0;
            int emptyCount = 0;
            int lastProcessedRound = -1;

            // Runde 0 starten: Initiator zündet als erster, dann Token absenden
            int fw0 = 0;
            Set<String> initOriginators = new HashSet<>();
            if (Math.random() < p) {
                for (String node : allNodes) {
                    if (!node.equals(nodeName())) send(new Firework(0, nodeName()), node);
                }
                fw0++;
                initOriginators.add(nodeName());
            }
            p /= 2;
            long t0 = System.nanoTime();
            send(new Token(0, fw0, initOriginators), nextNode); // Hier das Set mitgeben

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Firework(int round, String from) -> {
                        receivedInCurrentRound.add(from);
                    }
                    case Shutdown ignored -> { return; }

                    // Switch-Pattern entpackt jetzt auch das Set (originators)
                    case Token(int round, int fw, Set<String> originators) -> {
                        // Bei neuer Runde den lokalen Speicher leeren
                        if (round > lastProcessedRound) {
                            receivedInCurrentRound.clear();
                            lastProcessedRound = round;
                        }

                        // ERKENNUNGS-MECHANISMUS AUCH FÜR INITIATOR:
                        if (!receivedInCurrentRound.containsAll(originators)) {
                            System.err.printf("[INKONSISTENZ] %s: Token meldet Raketen von %s, aber real empfangen nur von %s%n",
                                    nodeName(), originators, receivedInCurrentRound);
                        }

                        // Runde abgeschlossen
                        long elapsed = System.nanoTime() - t0;
                        times.add(elapsed);
                        totalFw += fw;

                        if (fw == 0) emptyCount++;
                        else emptyCount = 0;

                        if (emptyCount >= k) {
                            // Terminieren: Shutdown an alle anderen senden
                            for (String node : allNodes) {
                                if (!node.equals(nodeName())) send(new Shutdown(), node);
                            }
                            saveStats(times, totalFw);
                            return;
                        }

                        // Nächste Runde starten
                        int newFw = 0;
                        Set<String> nextOriginators = new HashSet<>();
                        if (Math.random() < p) {
                            for (String node : allNodes) {
                                if (!node.equals(nodeName())) send(new Firework(round + 1, nodeName()), node);
                            }
                            newFw++;
                            nextOriginators.add(nodeName());
                        }
                        p /= 2;
                        t0 = System.nanoTime();
                        send(new Token(round + 1, newFw, nextOriginators), nextNode); // Hier das neue Set mitgeben
                    }
                    default -> throw new IllegalStateException("Unerwartete Nachricht: " + rm.message());
                }
            }
        }

        private void saveStats(List<Long> times, long totalFw) {
            rounds = times.size();
            broadcasts = totalFw;
            if (!times.isEmpty()) {
                minNs = times.stream().mapToLong(l -> l).min().orElse(0);
                maxNs = times.stream().mapToLong(l -> l).max().orElse(0);
                meanNs = times.stream().mapToLong(l -> l).average().orElse(0);
            }
        }
    }

    // ── Einstiegspunkt ───────────────────────────────────────────────────────

    public static void main(String[] args) {
        int n    = args.length > 0 ? Integer.parseInt(args[0])   : 8;
        double p0 = args.length > 1 ? Double.parseDouble(args[1]) : 0.5;
        int k    = args.length > 2 ? Integer.parseInt(args[2])   : 3;

        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add("Node-" + i);

        Simulator sim = Simulator.getInstance();
        Initiator init = new Initiator(names, k, p0);
        for (int i = 1; i < n; i++) new RingNode(i, names, p0);

        sim.simulate();
        sim.shutdown();

        // Einzige Ausgabezeile für das Experiment-Skript
        System.out.printf(
            "STATS n=%d rounds=%d broadcasts=%d min_rt_ms=%.3f mean_rt_ms=%.3f max_rt_ms=%.3f%n",
            n, init.rounds, init.broadcasts,
            init.minNs  / 1_000_000.0,
            init.meanNs / 1_000_000.0,
            init.maxNs  / 1_000_000.0
        );
    }
}