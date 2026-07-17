package org.oxoo2a.sim4da;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Aufgabe 3, Teil 2 (Statistik): NAIVER Schnappschuss ueber mehrere n.
 *
 * Der naive Snapshot fragt nur Kontostaende ab und ignoriert die Kanaele.
 * Ist beim Schnitt eine Ueberweisung unterwegs, weicht die erfasste Summe
 * von S ab ("Geld verschwindet" oder "entsteht").
 *
 * Pro Snapshot-Runde wird in eine CSV geloggt, wie stark und wie oft der
 * naive Schnitt daneben liegt. Gemessen wird u.a.:
 *   - capturedSum        : erfasste Summe der Konten
 *   - error              : capturedSum - S (0 = konsistent, sonst Abweichung)
 *   - absError           : Betrag der Abweichung
 *   - inconsistent       : 1 wenn error != 0, sonst 0
 * Daraus laesst sich z.B. die Inkonsistenz-Rate und der mittlere Fehler
 * ueber n darstellen.
 */
public class AccountSimulation_Ex3_2_Stats {

    record Token(int hopsLeft) implements Message {}
    record Transfer(int sender, int receiver, int amount) implements Message {}

    record SnapshotStart(int round) implements Message {}
    record SnapshotReport(int round, int id, int balance) implements Message {}

    record Stop() implements Message {}

    // geteilter Statistik-Kontext fuer den aktuellen Lauf
    static FileWriter csv;
    static int runN;
    static int runTokenInterval;

    static class ProcessNode extends Node {
        private final int id;
        private int balance;
        private final int partners;
        private final boolean isCoordinator;
        private final int expectedTotal;
        private final int tokenInterval;

        private int myRound = -1;

        // nur vom Koordinator genutzt
        private int currentRound = 0;
        private final int[] recordedBalances;
        private final boolean[] reported;
        private int reportCount = 0;
        private int balanceSum = 0;

        ProcessNode(int id, int balance, int partners, int expectedTotal, int tokenInterval) {
            super(String.valueOf(id));
            this.id = id;
            this.balance = balance;
            this.partners = partners;
            this.isCoordinator = (id == 0);
            this.expectedTotal = expectedTotal;
            this.tokenInterval = tokenInterval;
            this.recordedBalances = new int[partners];
            this.reported = new boolean[partners];
        }

        @Override
        protected void engage() {
            if (isCoordinator) {
                new Thread(() -> {
                    while (true) {
                        sleep(tokenInterval);
                        int s = new Random().nextInt(0, partners);
                        send(new Token(0), String.valueOf(s));
                    }
                }).start();

                new Thread(() -> {
                    while (true) {
                        sleep(1000);
                        send(new SnapshotStart(-1), String.valueOf(0));
                    }
                }).start();

                int s = new Random().nextInt(0, partners);
                send(new Token(0), String.valueOf(s));
            }

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case SnapshotStart s   -> handleSnapshotStart(s.round());
                    case SnapshotReport r  -> handleReport(r.round(), r.id(), r.balance());
                    case Token t           -> handleToken();
                    case Transfer t        -> handleTransfer(t);
                    case Stop s            -> { return; }
                    default -> { }
                }
            }
        }

        private void handleToken() {
            if (balance > 0) {
                int amount = new Random().nextInt(1, balance + 1);
                int receiver = id;
                while (receiver == id) {
                    receiver = new Random().nextInt(0, partners);
                }
                balance -= amount;
                // in-flight-Fenster: haelt die Ueberweisung kurz "unterwegs".
                sleep(Math.max(1, tokenInterval / 2));
                send(new Transfer(id, receiver, amount), String.valueOf(receiver));
            }
        }

        private void handleTransfer(Transfer t) {
            if (t.receiver != id) return;
            balance += t.amount;
        }

        private void handleSnapshotStart(int round) {
            if (isCoordinator && round == -1) {
                currentRound++;
                resetRound();
                broadcast(new SnapshotStart(currentRound));
                reportOwnBalance(currentRound);
            } else {
                reportOwnBalance(round);
            }
        }

        private void reportOwnBalance(int round) {
            myRound = round;
            send(new SnapshotReport(round, id, balance), String.valueOf(0));
        }

        // ---- ab hier nur Koordinator ----
        private void resetRound() {
            reportCount = 0;
            balanceSum = 0;
            for (int i = 0; i < partners; i++) reported[i] = false;
        }

        private void handleReport(int round, int pid, int bal) {
            if (round != currentRound) return;
            if (!reported[pid]) {
                reported[pid] = true;
                recordedBalances[pid] = bal;
                balanceSum += bal;
                reportCount++;
            }
            if (reportCount == partners) {
                logRound(round);
            }
        }

        private void logRound(int round) {
            int sum = balanceSum;
            int error = sum - expectedTotal;          // >0: Geld "entstanden", <0: "verschwunden"
            int absError = Math.abs(error);
            int inconsistent = (error != 0) ? 1 : 0;
            String kind = error == 0 ? "konsistent" : (error < 0 ? "verschwunden" : "entstanden");

            System.out.printf("[n=%d] Runde %d: Summe=%d (erwartet %d), Fehler=%d %s%n",
                    runN, round, sum, expectedTotal, error, kind);
            try {
                // Spalten: n,tokenInterval,round,capturedSum,expected,error,absError,inconsistent
                csv.write(String.format("%d,%d,%d,%d,%d,%d,%d,%d%n",
                        runN, runTokenInterval, round, sum, expectedTotal, error, absError, inconsistent));
                csv.flush();
            } catch (IOException e) {
                System.err.println("CSV-Schreibfehler: " + e.getMessage());
            }
        }
    }


    public static void main(String[] args) throws IOException {
        int[] nValues = {2, 4, 8, 16, 32, 64};
        int[] intervals = {300};   // Ueberweisungsfrequenz; erweiterbar, z.B. {150, 300, 600}
        int start = 1000;
        int runSeconds = 12;       // laenger -> mehr Runden pro n -> stabilere Statistik

        csv = new FileWriter("naive_snapshot_stats.csv");
        csv.write("n,tokenInterval,round,capturedSum,expected,error,absError,inconsistent\n");
        csv.flush();

        for (int interval : intervals) {
            for (int n : nValues) {
                runN = n;
                runTokenInterval = interval;

                Simulator simulator = Simulator.getInstance();
                List<ProcessNode> processes = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    processes.add(new ProcessNode(i, start, n, n * start, interval));
                }
                System.out.printf("%n### Lauf: n=%d, Intervall=%dms ###%n", n, interval);

                simulator.simulate(runSeconds);
                simulator.shutdown();
            }
        }

        csv.close();
        System.out.println("\nFertig. Statistik in naive_snapshot_stats.csv");
    }
}