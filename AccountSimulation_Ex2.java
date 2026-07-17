package org.oxoo2a.sim4da;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AccountSimulation_Ex2 {

    record Token(int hopsLeft) implements Message {}
    record Transfer(int sender, int receiver, int amount, String color) implements Message {}

    // Snapshot-Kontroll- und Meldenachrichten
    record SnapshotStart() implements Message {}
    record SnapshotReport(int id, int balance) implements Message {}
    record ChannelReport(int amount) implements Message {}

    record Stop() implements Message {}


    static class ProcessNode extends Node {
        private final int id;
        private int balance;
        private final int partners;
        private final boolean isCoordinator;
        private final int expectedTotal;
        private String color;

        // nur vom Koordinator genutzt
        private final int[] recordedBalances;
        private final boolean[] reported;
        private int reportCount = 0;
        private int balanceSum = 0;
        private int channelSum = 0;
        private boolean snapshotDone = false;

        ProcessNode(int id, int balance, int partners, int expectedTotal) {
            super(String.valueOf(id));
            this.id = id;
            this.balance = balance;
            this.partners = partners;
            this.isCoordinator = (id == 0);
            this.expectedTotal = expectedTotal;
            this.color = "white";
            this.recordedBalances = new int[partners];
            this.reported = new boolean[partners];
        }

        @Override
        protected void engage() {
            if (isCoordinator) {
                // Token-Verkehr
                new Thread(() -> {
                    while (true) {
                        sleep(100);
                        int s = new Random().nextInt(0, partners);
                        send(new Token(0), String.valueOf(s));
                    }
                }).start();

                // Snapshot nach 1s auslösen (an sich selbst -> läuft im Hauptthread)
                new Thread(() -> {
                    sleep(1000);
                    send(new SnapshotStart(), String.valueOf(0));
                }).start();

                int s = new Random().nextInt(0, partners);
                send(new Token(0), String.valueOf(s));
            }

            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case SnapshotStart s   -> handleSnapshotStart();
                    case SnapshotReport r  -> handleReport(r.id(), r.balance());
                    case ChannelReport c   -> handleChannel(c.amount());
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
                send(new Transfer(id, receiver, amount, color), String.valueOf(receiver));
            }
        }

        private void handleTransfer(Transfer t) {
            if (t.receiver != id) return;

            // Fall: schwarze Nachricht an noch weißen Prozess
            // -> erst eigenen Snapshot (Betrag zählt beim Sender, nicht bei mir)
            if (color.equals("white") && t.color.equals("black")) {
                doSnapshot();
            }

            boolean channelMoney = color.equals("black") && t.color.equals("white");
            balance += t.amount;
            System.out.printf("[Prozess %d]: erhalten %d€ von %d | neuer Kontostand: %d%n",
                    id, t.amount, t.sender, balance);

            // Fall: schwarzer Prozess empfängt weiße Nachricht -> Kanalzustand
            if (channelMoney) {
                send(new ChannelReport(t.amount), String.valueOf(0));
            }
        }

        // weiß -> schwarz, lokalen Zustand einfrieren und melden
        private void doSnapshot() {
            color = "black";
            send(new SnapshotReport(id, balance), String.valueOf(0));
        }

        private void handleSnapshotStart() {
            if (color.equals("white")) {
                if (isCoordinator) {
                    broadcast(new SnapshotStart());
                }
                doSnapshot();
            }
        }

        // ---- ab hier nur Koordinator ----
        private void handleReport(int pid, int bal) {
            if (!reported[pid]) {
                reported[pid] = true;
                recordedBalances[pid] = bal;
                balanceSum += bal;
                reportCount++;
            }
            checkDone();
        }

        private void handleChannel(int amount) {
            channelSum += amount;
            checkDone();
        }

        private void checkDone() {
            if (!snapshotDone && reportCount == partners && balanceSum + channelSum == expectedTotal) {
                snapshotDone = true;
                printSnapshot();
            }
        }

        private void printSnapshot() {
            System.out.println("\n========== GLOBALER SCHNAPPSCHUSS ==========");
            int sum = 0;
            for (int i = 0; i < partners; i++) {
                System.out.printf("Prozess %d: Kontostand = %d€%n", i, recordedBalances[i]);
                sum += recordedBalances[i];
            }
            System.out.printf("Kanalzustand (unterwegs): %d€%n", channelSum);
            System.out.printf("Summe (Konten + Kanäle): %d€ (erwartet: %d€)%n", sum + channelSum, expectedTotal);
            System.out.println((sum + channelSum == expectedTotal) ? "Invariante erfüllt ✓" : "Invariante VERLETZT ✗");
            System.out.println("============================================\n");
        }
    }


    public static void main(String[] args) {
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(Math::random);
        Simulator simulator = Simulator.getInstance();

        int n = 4;
        int start = 1000;
        List<ProcessNode> processes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            processes.add(new ProcessNode(i, start, n, n * start));
            System.out.printf("Process %d started.%n", i);
        }

        simulator.simulate(3);
        simulator.shutdown();
    }
}