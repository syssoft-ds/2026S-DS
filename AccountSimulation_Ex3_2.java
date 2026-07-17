package org.oxoo2a.sim4da;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Aufgabe 3, Teil 2: NAIVER Schnappschuss.
 *
 * Der Koordinator fragt nur die Kontostaende ab und IGNORIERT die Kanaele
 * (keine Faerbung, kein ChannelReport). Dadurch kann die erfasste Summe
 * von S abweichen, sobald beim Schnitt eine Ueberweisung unterwegs ist:
 * Der Sender hat den Betrag bereits abgezogen, der Empfaenger ihn noch
 * nicht gutgeschrieben -> "Geld verschwindet".
 *
 * Es werden fortlaufend Snapshot-RUNDEN ausgeloest; jede Runde hat eine
 * eigene Runden-ID, damit sich die Zaehler nicht ueber alte Snapshots
 * hinweg vermischen.
 */
public class AccountSimulation_Ex3_2 {

    record Token(int hopsLeft) implements Message {}
    record Transfer(int sender, int receiver, int amount) implements Message {}

    // Snapshot-Kontroll- und Meldenachrichten (mit Runden-ID)
    record SnapshotStart(int round) implements Message {}
    record SnapshotReport(int round, int id, int balance) implements Message {}

    record Stop() implements Message {}


    static class ProcessNode extends Node {
        private final int id;
        private int balance;
        private final int partners;
        private final boolean isCoordinator;
        private final int expectedTotal;

        // Runde, an der dieser Knoten zuletzt teilgenommen hat (-1 = noch keine)
        private int myRound = -1;

        // nur vom Koordinator genutzt
        private int currentRound = 0;
        private final int[] recordedBalances;
        private final boolean[] reported;
        private int reportCount = 0;
        private int balanceSum = 0;

        ProcessNode(int id, int balance, int partners, int expectedTotal) {
            super(String.valueOf(id));
            this.id = id;
            this.balance = balance;
            this.partners = partners;
            this.isCoordinator = (id == 0);
            this.expectedTotal = expectedTotal;
            this.recordedBalances = new int[partners];
            this.reported = new boolean[partners];
        }

        @Override
        protected void engage() {
            if (isCoordinator) {
                // Ueberweisungsverkehr. Token-Intervall > Empfaenger-Verzoegerung,
                // damit sich die Nachrichten-Queues nicht aufstauen.
                new Thread(() -> {
                    while (true) {
                        sleep(300);
                        int s = new Random().nextInt(0, partners);
                        send(new Token(0), String.valueOf(s));
                    }
                }).start();

                // Wiederholt Snapshot-Runden ausloesen (an sich selbst -> Hauptthread).
                new Thread(() -> {
                    while (true) {
                        sleep(1000);
                        send(new SnapshotStart(-1), String.valueOf(0)); // -1 = "neue Runde starten"
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
                System.out.printf("[%d]: Sende an %d %d€ | neuer Kontostand: %d%n", id, receiver, amount, balance);
                // Kurze Verzoegerung VOR dem Versand vergroessert das in-flight-Fenster,
                // ohne den Empfaenger-Loop zu blockieren.
                sleep(150);
                send(new Transfer(id, receiver, amount), String.valueOf(receiver));
            }
        }

        private void handleTransfer(Transfer t) {
            if (t.receiver != id) return;
            balance += t.amount;
            System.out.printf("[Prozess %d]: erhalten %d€ von %d | neuer Kontostand: %d%n",
                    id, t.amount, t.sender, balance);
        }

        // Naiver Snapshot: nur den lokalen Kontostand melden, Kanaele ignorieren.
        private void handleSnapshotStart(int round) {
            if (isCoordinator && round == -1) {
                // Neue Runde eroeffnen und Zaehler zuruecksetzen.
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
            if (round != currentRound) return; // veralteter Report -> ignorieren
            if (!reported[pid]) {
                reported[pid] = true;
                recordedBalances[pid] = bal;
                balanceSum += bal;
                reportCount++;
            }
            if (reportCount == partners) {
                printSnapshot(round);
            }
        }

        private void printSnapshot(int round) {
            System.out.printf("%n===== NAIVER SCHNAPPSCHUSS (Runde %d) =====%n", round);
            int sum = 0;
            for (int i = 0; i < partners; i++) {
                System.out.printf("Prozess %d: Kontostand = %d€%n", i, recordedBalances[i]);
                sum += recordedBalances[i];
            }
            System.out.printf("Summe der Konten: %d€ (erwartet: %d€)%n", sum, expectedTotal);
            if (sum == expectedTotal) {
                System.out.println("zufaellig konsistent (nichts war unterwegs)");
            } else {
                System.out.printf("INKONSISTENT: %d€ %s%n",
                        Math.abs(expectedTotal - sum),
                        sum < expectedTotal ? "verschwunden" : "entstanden");
            }
            System.out.println("==========================================\n");
        }
    }


    public static void main(String[] args) {
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(Math::random);
        Simulator simulator = Simulator.getInstance();

        int n = 5;
        int start = 1000;
        List<ProcessNode> processes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            processes.add(new ProcessNode(i, start, n, n * start));
            System.out.printf("Process %d started.%n", i);
        }

        simulator.simulate(10);
        simulator.shutdown();
    }
}