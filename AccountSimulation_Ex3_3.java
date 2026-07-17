package org.oxoo2a.sim4da;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aufgabe 3, Teil 3: Statistik.
 *
 * Der korrekte Schnappschuss (Einfaerbeverfahren) wird fuer verschiedene n
 * und Ueberweisungsfrequenzen mehrfach ausgeloest. Pro Snapshot-Runde wird
 * die Anzahl der KONTROLLNACHRICHTEN gezaehlt:
 *
 *     SnapshotStart (n)  +  SnapshotReport (n)  +  ChannelReport (variabel)
 *
 * Die Ergebnisse werden als CSV nach snapshot_stats.csv geschrieben und
 * koennen mit matplotlib ausgewertet werden.
 */
public class AccountSimulation_Ex3_3 {

    record Token(int hopsLeft) implements Message {}
    record Transfer(int sender, int receiver, int amount, int round) implements Message {}

    record SnapshotStart(int round) implements Message {}
    record SnapshotReport(int round, int id, int balance) implements Message {}
    record ChannelReport(int round, int amount) implements Message {}

    record Stop() implements Message {}

    // --- geteilter Statistik-Kontext fuer einen Lauf (ein bestimmtes n) ---
    // Wird pro Lauf in main neu gesetzt. Nur der Koordinator schreibt hinein.
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

        private int myRound = 0;

        // nur vom Koordinator genutzt
        private int currentRound = 0;
        private final int[] recordedBalances;
        private final boolean[] reported;
        private int reportCount = 0;
        private int balanceSum = 0;
        private int channelSum = 0;
        private boolean printedThisRound = false;

        // Kontrollnachrichten-Zaehler der aktuellen Runde (nur Koordinator)
        private int controlMsgCount = 0;
        private int channelMsgCount = 0;

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

                // Gut getrennte Runden: 1500ms Abstand -> jede Runde ist fertig,
                // bevor die naechste startet.
                new Thread(() -> {
                    while (true) {
                        sleep(1500);
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
                    case ChannelReport c   -> handleChannel(c.round(), c.amount());
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
                sleep(Math.max(1, tokenInterval / 2));
                send(new Transfer(id, receiver, amount, myRound), String.valueOf(receiver));
            }
        }

        private void handleTransfer(Transfer t) {
            if (t.receiver != id) return;

            if (t.round > myRound) {
                doSnapshot(t.round);
            }
            boolean channelMoney = t.round < myRound;
            balance += t.amount;
            if (channelMoney) {
                send(new ChannelReport(myRound, t.amount), String.valueOf(0));
            }
        }

        private void doSnapshot(int round) {
            myRound = round;
            send(new SnapshotReport(round, id, balance), String.valueOf(0));
        }

        private void handleSnapshotStart(int round) {
            if (isCoordinator && round == -1) {
                currentRound++;
                resetRound();
                // Kontrollnachrichten: 1 Broadcast erreicht (partners-1) Knoten,
                // plus der Koordinator schnappt selbst -> insgesamt n Start-Signale.
                controlMsgCount += partners;      // SnapshotStart an alle n
                broadcast(new SnapshotStart(currentRound));
                doSnapshot(currentRound);
            } else if (round > myRound) {
                doSnapshot(round);
            }
        }

        // ---- ab hier nur Koordinator ----
        private void resetRound() {
            reportCount = 0;
            balanceSum = 0;
            channelSum = 0;
            printedThisRound = false;
            controlMsgCount = 0;
            channelMsgCount = 0;
            for (int i = 0; i < partners; i++) reported[i] = false;
        }

        private void handleReport(int round, int pid, int bal) {
            if (round != currentRound) return;
            controlMsgCount++;               // jeder SnapshotReport ist eine Kontrollnachricht
            if (!reported[pid]) {
                reported[pid] = true;
                recordedBalances[pid] = bal;
                balanceSum += bal;
                reportCount++;
            }
            checkDone(round);
        }

        private void handleChannel(int round, int amount) {
            if (round != currentRound) return;
            controlMsgCount++;               // jeder ChannelReport ist eine Kontrollnachricht
            channelMsgCount++;
            channelSum += amount;
            checkDone(round);
        }

        private void checkDone(int round) {
            if (!printedThisRound && reportCount == partners && balanceSum + channelSum == expectedTotal) {
                printedThisRound = true;
                logRound(round);
            }
        }

        private void logRound(int round) {
            int sum = balanceSum + channelSum;
            boolean consistent = (sum == expectedTotal);
            System.out.printf("[n=%d, Intervall=%dms] Runde %d: Kontrollnachrichten=%d (davon Channel=%d), Summe=%d %s%n",
                    runN, runTokenInterval, round, controlMsgCount, channelMsgCount, sum, consistent ? "OK" : "FEHLER");
            try {
                // Spalten: n,tokenInterval,round,controlMessages,channelMessages,capturedSum,consistent
                csv.write(String.format("%d,%d,%d,%d,%d,%d,%d%n",
                        runN, runTokenInterval, round, controlMsgCount, channelMsgCount, sum, consistent ? 1 : 0));
                csv.flush();
            } catch (IOException e) {
                System.err.println("CSV-Schreibfehler: " + e.getMessage());
            }
        }
    }


    public static void main(String[] args) throws IOException {
        int[] nValues = {2, 4, 8, 16, 32, 64};
        int[] intervals = {300};   // Ueberweisungsfrequenz: hier ein Intervall; erweiterbar
        int start = 1000;
        int runSeconds = 8;

        csv = new FileWriter("snapshot_stats.csv");
        csv.write("n,tokenInterval,round,controlMessages,channelMessages,capturedSum,consistent\n");
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
                simulator.shutdown();   // Framework fuer den naechsten Lauf zuruecksetzen
            }
        }

        csv.close();
        System.out.println("\nFertig. Statistik in snapshot_stats.csv");
    }
}