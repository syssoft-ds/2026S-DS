package org.oxoo2a.test;

import org.junit.jupiter.api.Test;
import org.oxoo2a.sim4da.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class BankhausKonsistenz {

    static int N = 5;                       // Anzahl Prozesse
    static final int START_BALANCE = 1000;  // Startsaldo pro Prozess
    static final int RUNTIME_SECONDS = 15;  // Laufzeitbegrenzung
    static final int SNAPSHOTS = 3;         // Schnappschuss-Runden
    static final String INITIATOR = "0";    // Prozess, der den Schnappschuss startet

    record Transfer(int amount, int round) implements Message {}
    record Tick() implements Message {}
    record Marker(int round, int sentOnChannel) implements Message {}
    record Report(int balance, List<String> channels, int channelSum) implements Message {}
    record BalanceRequest() implements Message {}       // naiver Schnappschuss
    record BalanceReport(int balance) implements Message {}

    static class BankNode extends Node {

        private int balance = START_BALANCE;
        private final Random random = new Random();
        private long startTime;
        // Chandy-Lamport-Zustand 
        private int round = 0;
        private boolean reportSent = true;
        private int recordedBalance;
        private int[] sentThisRound = new int[N];  // in aktueller Runde gesendet, je Kanal
        private int[] receivedThisRound = new int[N]; // in aktueller Runde empfangen
        private int[] receivedPrev = new int[N];   // Vorrunden-Empfänge (für Kanal-Bilanz)
        private final int[] expected = new int[N]; // laut Marker zu erwartende Vorrunden-Nachrichten
        private final int[] recordedCnt = new int[N];
        private List<String> channelLog = new ArrayList<>();
        private int channelSum = 0;
        // nur Initiator: Ablaufsteuerung und Einsammeln
        private long nextActionAt;
        private boolean busy = false;
        private int done = 0, naiveSum = 0, naiveReplies = 0;
        private int reports = 0, balanceSum = 0, allChannelSum = 0, allChannelCount = 0;

        BankNode(int id) { super(String.valueOf(id)); }

        @Override
        protected void engage() {
            startTime = System.currentTimeMillis();
            nextActionAt = startTime + 2000;
            Arrays.fill(expected, -1);
            send(new Tick(), nodeName());
            while (!Thread.currentThread().isInterrupted()) {
                ReceivedMessage rm = receive();
                if (rm == null) break;
                switch (rm.message()) {
                    case Tick t -> {
                        sleep(random.nextInt(50, 300));
                        // Initiator: nächste Runde "naiv, dann konsistent" starten
                        if (nodeName().equals(INITIATOR) && !busy && done < SNAPSHOTS
                                && System.currentTimeMillis() >= nextActionAt) {
                            busy = true;
                            naiveSum = balance; // naiv: nur Salden, Kanäle ignoriert
                            naiveReplies = 0;
                            broadcast(new BalanceRequest());
                        }
                        transferToRandomNode();
                        send(new Tick(), nodeName());
                    }
                    case Transfer(int amount, int msgRound) -> {
                        if (msgRound > round) takeSnapshot(msgRound); // Nachricht aus der Zukunft
                        balance += amount;
                        int from = Integer.parseInt(rm.sender());
                        if (msgRound == round) receivedThisRound[from]++;
                        else { // Vorrunde: war beim Schnitt unterwegs -> Kanalzustand
                            channelLog.add(from + "->" + nodeName() + ":" + amount);
                            channelSum += amount;
                            recordedCnt[from]++;
                            checkChannelsComplete();
                        }
                    }
                    case Marker(int mRound, int sent) -> {
                        if (mRound > round) takeSnapshot(mRound);
                        expected[Integer.parseInt(rm.sender())] = sent;
                        checkChannelsComplete();
                    }
                    case BalanceRequest b -> send(new BalanceReport(balance), INITIATOR);
                    case BalanceReport(int b) -> { // nur Initiator
                        naiveSum += b;
                        if (++naiveReplies == N - 1) {
                            System.out.printf("NAIV:       Summe Konten = %5d  (S = %d)  %s%n",
                                    naiveSum, N * START_BALANCE, naiveSum == N * START_BALANCE
                                            ? "zufaellig konsistent" : "INKONSISTENT");
                            takeSnapshot(round + 1); // direkt danach: konsistenter Schnappschuss
                        }
                    }
                    case Report(int b, List<String> ch, int cs) -> collect(b, ch, cs);
                    default -> throw new IllegalStateException("Unerwartete Nachricht");
                }
            }
        }

        // Zustand sichern, Rundenzähler umschalten, Marker mit den Vorrunden-Sendezählern auf jeden ausgehenden Kanal schicken
        private void takeSnapshot(int newRound) {
            round = newRound;
            recordedBalance = balance;
            reportSent = false;
            int[] sentPrev = sentThisRound;
            sentThisRound = new int[N];
            receivedPrev = receivedThisRound;
            receivedThisRound = new int[N];
            Arrays.fill(expected, -1);
            Arrays.fill(recordedCnt, 0);
            channelLog = new ArrayList<>();
            channelSum = 0;
            for (int j = 0; j < N; j++)
                if (j != Integer.parseInt(nodeName()))
                    send(new Marker(round, sentPrev[j]), String.valueOf(j));
        }

        // Wie Aufgabe 2: Kanal j fertig, wenn Marker da und alle
        // Vorrunden-Nachrichten eingetroffen, dann Teilzustand melden
        private void checkChannelsComplete() {
            if (reportSent) return;
            for (int j = 0; j < N; j++) {
                if (j == Integer.parseInt(nodeName())) continue;
                if (expected[j] < 0 || receivedPrev[j] + recordedCnt[j] != expected[j]) return;
            }
            reportSent = true;
            send(new Report(recordedBalance, List.copyOf(channelLog), channelSum), INITIATOR);
        }

        // Nur Initiator: Teilzustände einsammeln, Ergebnis + Statistik ausgeben
        private void collect(int b, List<String> ch, int cs) {
            balanceSum += b;
            allChannelSum += cs;
            allChannelCount += ch.size();
            if (++reports < N) return;
            // Kontrollnachrichten: n*(n-1) Marker + n Reports
            System.out.printf("KONSISTENT: Konten = %5d + Kanaele = %5d  ->  %5d  (S = %d)  " +
                            "[Kontrollnachrichten: %d, davon Kanaleintraege in Reports: %d]%n",
                    balanceSum, allChannelSum, balanceSum + allChannelSum,
                    N * START_BALANCE, N * (N - 1) + N, allChannelCount);
            reports = 0; balanceSum = 0; allChannelSum = 0; allChannelCount = 0;
            done++;
            busy = false;
            nextActionAt = System.currentTimeMillis() + 2000;
        }

        private void transferToRandomNode() {
            if (balance <= 0) return;
            int amount = random.nextInt(1, balance + 1);
            int receiver;
            do { receiver = random.nextInt(N); } while (receiver == Integer.parseInt(nodeName()));
            balance -= amount;
            sentThisRound[receiver]++;
            sendWithLatency(new Transfer(amount, round), String.valueOf(receiver));
        }

        private void sendWithLatency(Message message, String receiver) {
            int latency = random.nextInt(100, 1000);
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(latency); }
                catch (InterruptedException e) { return; }
                send(message, receiver);
            });
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) N = Integer.parseInt(args[0]);
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                RandomValues.getUniformDistribution());
        Simulator simulator = Simulator.getInstance();
        for (int i = 0; i < N; i++) new BankNode(i);
        System.out.printf("Bankhaus: n = %d, S = %d, %d naive + %d konsistente Schnappschuesse%n",
                N, N * START_BALANCE, SNAPSHOTS, SNAPSHOTS);
        simulator.simulate(RUNTIME_SECONDS);
        simulator.shutdown();
    }

    // Testmethode, um die Simulation in der IDE zu starten
    @Test
        void run() {
            main(new String[0]);
        }
}