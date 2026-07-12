package org.oxoo2a.test;

import org.oxoo2a.sim4da.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


public class BankhausSnapshot {

    static final int N = 5;                 // Anzahl Prozesse
    static final int START_BALANCE = 1000;  // Startsaldo pro Prozess
    static final int RUNTIME_SECONDS = 10;  // Laufzeitbegrenzung
    static final int SNAPSHOT_AT_MS = 3000; // Prozess 0 initiiert nach 3 s
    static final String INITIATOR = "0";    // Prozess, der den Schnappschuss startet

    record Transfer(int amount, boolean pre) implements Message {}
    record Tick() implements Message {}
    record Marker(int sentOnChannel) implements Message {}
    record Report(int balance, List<String> channels, int channelSum) implements Message {}

    static class BankNode extends Node {

        private int balance = START_BALANCE;
        private final Random random = new Random();
        private long startTime;
        // Schnappschuss-Zustand
        private boolean recorded = false, reportSent = false;
        private int recordedBalance;
        private final int[] sentPre = new int[N];     // pre gesendet, je Kanal
        private final int[] receivedPre = new int[N]; // pre vor eigenem Schnitt empfangen
        private final int[] expected = new int[N];    // laut Marker zu erwartende pre-Nachrichten
        private final int[] recordedCnt = new int[N]; // aufgezeichnete Kanalnachrichten
        private final List<String> channelLog = new ArrayList<>();
        private int channelSum = 0;

        // nur beim Initiator: Einsammeln der Teilzustände
        private int reports = 0, balanceSum = 0, allChannelSum = 0;
        private final int[] balances = new int[N];
        private final List<String> allChannels = new ArrayList<>();

        BankNode(int id) { super(String.valueOf(id)); }

        @Override
        protected void engage() {
            startTime = System.currentTimeMillis();
            Arrays.fill(expected, -1);
            send(new Tick(), nodeName());
            while (!Thread.currentThread().isInterrupted()) {
                ReceivedMessage rm = receive();
                if (rm == null) break;
                switch (rm.message()) {
                    case Tick t -> {
                        sleep(random.nextInt(50, 300));
                        if (nodeName().equals(INITIATOR) && !recorded
                                && System.currentTimeMillis() - startTime >= SNAPSHOT_AT_MS)
                            takeSnapshot(); // Initiator startet den Schnappschuss
                        transferToRandomNode();
                        send(new Tick(), nodeName());
                    }
                    case Transfer(int amount, boolean pre) -> {
                        // post-Nachricht hat den Marker überholt -> erst Zustand sichern, dann verbuchen
                        if (!pre && !recorded) takeSnapshot();
                        balance += amount;
                        int from = Integer.parseInt(rm.sender());
                        if (pre) {
                            if (!recorded) receivedPre[from]++; // steckt im gesicherten Saldo
                            else { // war zum Schnittzeitpunkt unterwegs -> Kanalzustand
                                channelLog.add("Kanal " + from + " -> " + nodeName() + ": " + amount);
                                channelSum += amount;
                                recordedCnt[from]++;
                                checkChannelsComplete();
                            }
                        }
                    }
                    case Marker(int sent) -> { // erster Marker sichert den Zustand
                        if (!recorded) takeSnapshot();
                        expected[Integer.parseInt(rm.sender())] = sent;
                        checkChannelsComplete();
                    }
                    case Report(int b, List<String> ch, int cs) -> collect(rm.sender(), b, ch, cs);
                    default -> throw new IllegalStateException("Unerwartete Nachricht");
                }
            }
        }

        // Lokalen Zustand sichern und Marker (mit pre-Zähler) auf jeden ausgehenden Kanal senden
        private void takeSnapshot() {
            recorded = true;
            recordedBalance = balance;
            for (int j = 0; j < N; j++)
                if (j != Integer.parseInt(nodeName()))
                    send(new Marker(sentPre[j]), String.valueOf(j));
        }

        // Kanal j ist fertig, wenn sein Marker da ist und alle pre-Nachrichten eingetroffen sind (vor dem Schnitt empfangen oder aufgezeichnet)
        private void checkChannelsComplete() {
            if (reportSent) return;
            for (int j = 0; j < N; j++) {
                if (j == Integer.parseInt(nodeName())) continue;
                if (expected[j] < 0 || receivedPre[j] + recordedCnt[j] != expected[j]) return;
            }
            
            // Sind alle Kanäle fertig, geht der Teilzustand an den Initiator
            reportSent = true;
            send(new Report(recordedBalance, List.copyOf(channelLog), channelSum), INITIATOR);
        }

        // Nur der Initiator: Teilzustände einsammeln und am Ende ausgeben
        private void collect(String sender, int b, List<String> ch, int cs) {
            balances[Integer.parseInt(sender)] = b;
            balanceSum += b;
            allChannels.addAll(ch);
            allChannelSum += cs;
            if (++reports < N) return;
            System.out.println(">>> Schnappschuss abgeschlossen -- globaler Zustand:");
            for (int i = 0; i < N; i++) System.out.printf("    Konto %d: %d%n", i, balances[i]);
            allChannels.forEach(c -> System.out.println("    " + c));
            System.out.printf(">>> Summe Konten = %d, Summe Kanaele = %d, gesamt = %d (S = %d)%n",
                    balanceSum, allChannelSum, balanceSum + allChannelSum, N * START_BALANCE);
        }

        private void transferToRandomNode() {
            if (balance <= 0) return;
            int amount = random.nextInt(1, balance + 1);
            int receiver;
            do { receiver = random.nextInt(N); } while (receiver == Integer.parseInt(nodeName()));
            balance -= amount;               // Konto sofort reduzieren
            if (!recorded) sentPre[receiver]++;
            // pre-Flag gilt ab der Abbuchung (logischer Sendezeitpunkt)
            // die Zustellung erfolgt erst nach zufälliger Latenz
            sendWithLatency(new Transfer(amount, !recorded), String.valueOf(receiver));
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
        // FIFO-Zustellung abschalten
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                RandomValues.getUniformDistribution());
        Simulator simulator = Simulator.getInstance();
        for (int i = 0; i < N; i++) new BankNode(i);
        System.out.printf("Bankhaus mit %d Prozessen gestartet, Gesamtsumme S = %d%n",
                N, N * START_BALANCE);
        simulator.simulate(RUNTIME_SECONDS);
        simulator.shutdown();
    }

    // Testmethode, um die Simulation in der IDE zu starten
    @Test
        void run() {
            main(new String[0]);
        }
}