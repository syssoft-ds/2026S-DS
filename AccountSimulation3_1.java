package org.oxoo2a.sim4da;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Aufgabe 3, Teil 1: KORREKTER Schnappschuss (Einfaerbeverfahren) waehrend
 * des laufenden Betriebs.
 *
 * Es werden fortlaufend Snapshot-RUNDEN ausgeloest, waehrend die
 * Ueberweisungen weiterlaufen. In JEDER Runde muss gelten:
 *
 *     Summe der Kontostaende + Summe der Kanalinhalte == S
 *
 * Jede Runde hat eine eigene Runden-ID; die Knoten werden zu Beginn einer
 * Runde wieder weiss, damit auch die Folge-Snapshots sauber laufen.
 */
public class AccountSimulation3_1 {

    record Token(int hopsLeft) implements Message {}
    // Basisnachricht traegt die Farbe des Senders (round) zum Sendezeitpunkt.
    record Transfer(int sender, int receiver, int amount, int round) implements Message {}

    // Snapshot-Kontroll- und Meldenachrichten (mit Runden-ID)
    record SnapshotStart(int round) implements Message {}
    record SnapshotReport(int round, int id, int balance) implements Message {}
    record ChannelReport(int round, int amount) implements Message {}

    record Stop() implements Message {}


    static class ProcessNode extends Node {
        private final int id;
        private int balance;
        private final int partners;
        private final boolean isCoordinator;
        private final int expectedTotal;

        // Farbe wird ueber die "Runde" ausgedrueckt:
        // myRound = r  bedeutet, der Knoten hat fuer Runde r bereits geschnappt (= "schwarz" fuer r).
        // Eine Transfer-Nachricht ist "weiss" bzgl. Runde r, wenn ihr Feld round < r ist.
        private int myRound = 0;

        // nur vom Koordinator genutzt
        private int currentRound = 0;
        private final int[] recordedBalances;
        private final boolean[] reported;
        private int reportCount = 0;
        private int balanceSum = 0;
        private int channelSum = 0;
        private boolean printedThisRound = false;

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
                // Ueberweisungsverkehr. Intervall > Empfaenger-Verzoegerung -> kein Queue-Stau.
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
                System.out.printf("[%d]: Sende an %d %d€ | neuer Kontostand: %d%n", id, receiver, amount, balance);
                // Kurze Verzoegerung vergroessert das in-flight-Fenster.
                sleep(150);
                // Nachricht traegt die aktuelle "Farbe" (myRound) des Senders.
                send(new Transfer(id, receiver, amount, myRound), String.valueOf(receiver));
            }
        }

        private void handleTransfer(Transfer t) {
            if (t.receiver != id) return;

            // Fall: Nachricht wurde in einer neueren Runde abgeschickt als mein
            // aktueller Snapshot ("schwarze" Nachricht an noch "weissen" Prozess)
            // -> erst eigenen Snapshot fuer diese Runde, dann verbuchen.
            if (t.round > myRound) {
                doSnapshot(t.round);
            }

            // Fall: ich habe fuer Runde myRound bereits geschnappt und empfange eine
            // Nachricht, die VOR dieser Runde abgeschickt wurde ("weisse" Nachricht
            // an "schwarzen" Prozess) -> Geld war beim Schnitt unterwegs -> Kanalzustand.
            boolean channelMoney = t.round < myRound;

            balance += t.amount;
            System.out.printf("[Prozess %d]: erhalten %d€ von %d | neuer Kontostand: %d%n",
                    id, t.amount, t.sender, balance);

            if (channelMoney) {
                send(new ChannelReport(myRound, t.amount), String.valueOf(0));
            }
        }

        // Auf die uebergebene Runde schnappen: Farbe/Runde setzen und Kontostand melden.
        private void doSnapshot(int round) {
            myRound = round;
            send(new SnapshotReport(round, id, balance), String.valueOf(0));
        }

        private void handleSnapshotStart(int round) {
            if (isCoordinator && round == -1) {
                // Neue Runde eroeffnen und Koordinator-Zaehler zuruecksetzen.
                currentRound++;
                resetRound();
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
            checkDone(round);
        }

        private void handleChannel(int round, int amount) {
            if (round != currentRound) return;
            channelSum += amount;
            checkDone(round);
        }

        private void checkDone(int round) {
            // Fertig, sobald alle lokalen Zustaende da sind UND die erfasste
            // Gesamtsumme (Konten + Kanaele) wieder S ergibt.
            if (!printedThisRound && reportCount == partners && balanceSum + channelSum == expectedTotal) {
                printedThisRound = true;
                printSnapshot(round);
            }
        }

        private void printSnapshot(int round) {
            System.out.printf("%n===== GLOBALER SCHNAPPSCHUSS (Runde %d) =====%n", round);
            int sum = 0;
            for (int i = 0; i < partners; i++) {
                System.out.printf("Prozess %d: Kontostand = %d€%n", i, recordedBalances[i]);
                sum += recordedBalances[i];
            }
            System.out.printf("Kanalzustand (unterwegs): %d€%n", channelSum);
            System.out.printf("Summe (Konten + Kanaele): %d€ (erwartet: %d€)%n", sum + channelSum, expectedTotal);
            System.out.println((sum + channelSum == expectedTotal) ? "Invariante erfuellt ✓" : "Invariante VERLETZT ✗");
            System.out.println("===========================================\n");
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

        simulator.simulate(10);
        simulator.shutdown();
    }
}