package ringfeuerwerk;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;

/**
 * Aufgabe 4: Erweiterung von RingFeuerwerkSimulation (Aufgabe 3) um
 * Konsistenzpruefungen.
 *
 * Kriterium 1 (Rundenkonsistenz): Jeder Knoten erwartet, dass die im
 * Token mitgefuehrte Rundennummer genau eins hoeher ist als die zuletzt
 * gesehene. Abweichungen wuerden auf Token-Verlust/-Duplikation hindeuten.
 *
 * Kriterium 2 (Broadcast-Vollstaendigkeit): Jedes gesendete Feuerwerk
 * muss bei genau n-1 anderen Knoten ankommen. Also muss gelten:
 * Summe(empfangene Fireworks) == totalFires * (n-1).
 *
 * In sim4da sind send()/broadcast() zuverlaessig -> beide Kriterien
 * sollten hier IMMER erfuellt sein. Bei Aufgabe 1/2 (UDP, Multicast)
 * sind beide Kriterien dagegen nicht garantiert.
 */
public class RingFeuerwerkKonsistenz {

    record Token(boolean firedThisRound, int round) implements Message {}
    record Firework(String from, int round) implements Message {}
    record Stop() implements Message {}

    record RunResult(int n, int k, int totalFires, int rounds,
                     double minRoundMs, double meanRoundMs, double maxRoundMs,
                     long wallTimeMs, boolean roundOk, boolean broadcastOk) {

        String summaryLine() {
            return String.format(Locale.US,
                    "SUMMARY n=%d k=%d totalFires=%d rounds=%d minRoundMs=%.4f meanRoundMs=%.4f maxRoundMs=%.4f wallTimeMs=%d roundOk=%s broadcastOk=%s",
                    n, k, totalFires, rounds, minRoundMs, meanRoundMs, maxRoundMs, wallTimeMs, roundOk, broadcastOk);
        }
    }

    static class RingNode extends Node {

        private final int id;
        private final String nextId;
        private final boolean isCoordinator;
        private final int k;
        private final boolean verbose;

        private double p = 0.5;
        private int fireCount = 0;

        private int localRound = 0;
        private boolean roundViolation = false;
        private int receivedFireworks = 0;

        private int noFireCounter = 0;
        private long lastSendTime;
        private final List<Long> roundTimesNanos = new ArrayList<>();

        RingNode(int id, int n, int k, boolean verbose) {
            super(String.valueOf(id));
            this.id = id;
            this.nextId = String.valueOf((id + 1) % n);
            this.isCoordinator = (id == 0);
            this.k = k;
            this.verbose = verbose;
        }

        @Override
        protected void engage() {
            if (isCoordinator) {
                lastSendTime = System.nanoTime();
                send(new Token(false, 1), nextId);
            }

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;

                switch (rm.message()) {
                    case Token(boolean firedThisRound, int round) -> {
                        if (!handleToken(firedThisRound, round)) {
                            return;
                        }
                    }
                    case Firework f -> {
                        receivedFireworks++;
                        if (verbose) {
                            System.out.printf("[Prozess %d] Feuerwerk empfangen von %s (Runde %d)%n",
                                    id, f.from(), f.round());
                        }
                    }
                    case Stop s -> {
                        if (!isCoordinator) {
                            send(s, nextId);
                        }
                        printStats();
                        return;
                    }
                    default -> throw new IllegalStateException("Unerwartete Nachricht: " + rm.message());
                }
            }
        }

        private boolean handleToken(boolean firedThisRound, int round) {
            if (round != localRound + 1) {
                roundViolation = true;
                System.out.printf(
                        "[Prozess %d] INKONSISTENZ: erwartete Runde %d, Token enthaelt Runde %d%n",
                        id, localRound + 1, round);
            }
            localRound = round;

            if (verbose) {
                System.out.printf("[Prozess %d] Token erhalten (Runde %d), p=%.4f%n", id, round, p);
            }

            if (isCoordinator) {
                if (firedThisRound) noFireCounter = 0;
                else noFireCounter++;

                if (noFireCounter == k) {
                    if (verbose) {
                        System.out.printf("[Prozess %d] %d Runden ohne Zuendung -> STOP%n", id, k);
                    }
                    send(new Stop(), nextId);
                    printStats();
                    return false;
                }
            }

            boolean fireNow = Math.random() < p;
            if (fireNow) {
                if (verbose) {
                    System.out.printf("[Prozess %d] ZUeNDET! Sende Feuerwerk-Broadcast%n", id);
                }
                fireCount++;
                broadcast(new Firework("Prozess " + id, round));
            }

            p = p / 2;

            boolean newFlag;
            int newRound;
            if (isCoordinator) {
                long now = System.nanoTime();
                roundTimesNanos.add(now - lastSendTime);
                lastSendTime = now;
                newFlag = fireNow;
                newRound = round + 1;
            } else {
                newFlag = firedThisRound || fireNow;
                newRound = round;
            }

            send(new Token(newFlag, newRound), nextId);
            return true;
        }

        private void printStats() {
            if (verbose) {
                System.out.printf("STATS node=%d fires=%d%n", id, fireCount);
                if (isCoordinator) {
                    List<Double> roundTimesMs = roundTimesNanos.stream()
                            .map(ns -> ns / 1_000_000.0)
                            .toList();
                    System.out.printf("STATS rounds=%d roundTimesMs=%s%n",
                            roundTimesMs.size(), roundTimesMs);
                }
            }
        }

        int getFireCount() { return fireCount; }
        List<Long> getRoundTimesNanos() { return roundTimesNanos; }
        boolean hasRoundViolation() { return roundViolation; }
        int getReceivedFireworks() { return receivedFireworks; }
    }

    static RunResult runSimulation(int n, int k, boolean verbose) {
        Simulator simulator = Simulator.getInstance();
        if (!verbose) {
            simulator.disableLogging();
        }

        List<RingNode> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nodes.add(new RingNode(i, n, k, verbose));
        }

        long start = System.nanoTime();
        simulator.simulate();
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        simulator.shutdown();

        int totalFires = nodes.stream().mapToInt(RingNode::getFireCount).sum();
        List<Long> roundTimesNanos = nodes.get(0).getRoundTimesNanos();

        DoubleSummaryStatistics roundStats = roundTimesNanos.stream()
                .mapToDouble(ns -> ns / 1_000_000.0)
                .summaryStatistics();

        boolean roundOk = nodes.stream().noneMatch(RingNode::hasRoundViolation);
        int totalReceivedFireworks = nodes.stream().mapToInt(RingNode::getReceivedFireworks).sum();
        int expectedReceived = totalFires * (n - 1);
        boolean broadcastOk = (totalReceivedFireworks == expectedReceived);

        System.out.printf(Locale.US,
                "CONSISTENCY n=%d round=%s broadcast=%s (received=%d expected=%d)%n",
                n, roundOk ? "OK" : "VIOLATION", broadcastOk ? "OK" : "VIOLATION",
                totalReceivedFireworks, expectedReceived);

        return new RunResult(n, k, totalFires, roundTimesNanos.size(),
                roundStats.getMin(), roundStats.getAverage(), roundStats.getMax(),
                durationMs, roundOk, broadcastOk);
    }

    public static void main(String[] args) {
        int n = (args.length > 0) ? Integer.parseInt(args[0]) : 5;
        int k = (args.length > 1) ? Integer.parseInt(args[1]) : 3;
        boolean verbose = (args.length > 2) && Boolean.parseBoolean(args[2]);

        RunResult result = runSimulation(n, k, verbose);
        System.out.println(result.summaryLine());
    }
}