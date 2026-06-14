package ringfeuerwerk;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Locale;

public class RingFeuerwerkSimulation {

    // Das "Streichholz" — wandert im Ring weiter.
    // firedThisRound: wurde in dieser Runde (bisher) schon irgendwo gezündet?
    record Token(boolean firedThisRound) implements Message {}

    // Die "Feuerwerksrakete" — entspricht der UDP-Multicast-Nachricht in ring_process.py
    record Firework(String from) implements Message {}

    // Terminierungssignal — läuft einmal durch den Ring, dann Schluss
    record Stop() implements Message {}

    /** Ergebnis eines einzelnen Simulationslaufs. */
    record RunResult(int n, int k, int totalFires, int rounds,
                     double minRoundMs, double meanRoundMs, double maxRoundMs,
                     long wallTimeMs) {

        String summaryLine() {
            return String.format(Locale.US,
                    "SUMMARY n=%d k=%d totalFires=%d rounds=%d minRoundMs=%.4f meanRoundMs=%.4f maxRoundMs=%.4f wallTimeMs=%d",
                    n, k, totalFires, rounds, minRoundMs, meanRoundMs, maxRoundMs, wallTimeMs);
        }

        String csvLine() {
            return String.format(Locale.US,
                    "%d,%d,%d,%d,%.4f,%.4f,%.4f,%d",
                    n, k, totalFires, rounds, minRoundMs, meanRoundMs, maxRoundMs, wallTimeMs);
        }
    }

    static class RingNode extends Node {

        private final int id;
        private final String nextId;
        private final boolean isCoordinator; // entspricht "my_id == 0"
        private final int k;
        private final boolean verbose;

        private double p = 0.5;
        private int fireCount = 0;

        // nur fuer Knoten 0 relevant:
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
                // entspricht: sock.sendto(b"TOKEN:0", next) nach der READY-Phase.
                // Die READY-Phase brauchen wir hier nicht: der Simulator startet
                // erst, wenn alle Knoten existieren -> alle sind schon "bereit".
                lastSendTime = System.nanoTime();
                send(new Token(false), nextId);
            }

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return; // Simulation wurde von aussen beendet

                switch (rm.message()) {
                    case Token(boolean firedThisRound) -> {
                        if (!handleToken(firedThisRound)) {
                            return; // Knoten 0 hat STOP ausgeloest und beendet sich
                        }
                    }
                    case Firework f -> {
                        if (verbose) {
                            System.out.printf("[Prozess %d] Feuerwerk empfangen von %s%n", id, f.from());
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

        /** @return false, wenn dieser Knoten (Knoten 0) die Simulation gerade beendet hat */
        private boolean handleToken(boolean firedThisRound) {
            if (verbose) {
                System.out.printf("[Prozess %d] Token erhalten, p=%.4f%n", id, p);
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
                broadcast(new Firework("Prozess " + id));
            }

            p = p / 2;

            boolean newFlag;
            if (isCoordinator) {
                long now = System.nanoTime();
                roundTimesNanos.add(now - lastSendTime);
                lastSendTime = now;
                newFlag = fireNow;
            } else {
                newFlag = firedThisRound || fireNow;
            }

            send(new Token(newFlag), nextId);
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

        int getFireCount() {
            return fireCount;
        }

        List<Long> getRoundTimesNanos() {
            return roundTimesNanos;
        }
    }

    /** Fuehrt einen kompletten Simulationslauf mit n Knoten durch und liefert die Statistik zurueck. */
    static RunResult runSimulation(int n, int k, boolean verbose) {
        Simulator simulator = Simulator.getInstance();
        if (!verbose) {
            simulator.disableLogging(); // keine sim4da-<PID>.log-Dateien bei den Experimenten
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

        return new RunResult(n, k, totalFires, roundTimesNanos.size(),
                roundStats.getMin(), roundStats.getAverage(), roundStats.getMax(),
                durationMs);
    }

    /** Einzelner Lauf, wie bisher: n, k, verbose ueber argv. */
    private static void runSingle(String[] args) {
        int n = (args.length > 0) ? Integer.parseInt(args[0]) : 5;
        int k = (args.length > 1) ? Integer.parseInt(args[1]) : 3;
        boolean verbose = (args.length > 2) && Boolean.parseBoolean(args[2]);

        RunResult result = runSimulation(n, k, verbose);
        System.out.println(result.summaryLine());
    }

    /** Experiment: n verdoppeln, bis es scheitert (Fehler/OOM) oder die Liste durchlaufen ist. */
    private static void runExperiments() throws IOException {
        int k = 3;
        int[] ns = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072};
        long maxWallTimeMs = 5 * 60 * 1000; // 5 Minuten praktische Grenze

        try (PrintWriter csv = new PrintWriter(new FileWriter("results.csv"))) {
            csv.println("n,k,totalFires,rounds,minRoundMs,meanRoundMs,maxRoundMs,wallTimeMs");

            for (int n : ns) {
                try {
                    RunResult result = runSimulation(n, k, false);
                    System.out.println(result.summaryLine());
                    csv.println(result.csvLine());
                    csv.flush();

                    if (result.wallTimeMs() > maxWallTimeMs) {
                        System.out.println("Praktische Grenze erreicht bei n=" + n
                                + " (Laufzeit " + result.wallTimeMs() + "ms > " + maxWallTimeMs + "ms) -> Abbruch.");
                        break;
                    }
                } catch (Throwable t) {
                    System.out.println("n=" + n + " FEHLGESCHLAGEN: " + t);
                    break;
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("experiment")) {
            runExperiments();
        } else {
            runSingle(args);
        }
    }
}