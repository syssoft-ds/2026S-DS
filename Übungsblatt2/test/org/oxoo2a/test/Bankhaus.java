package org.oxoo2a.test;

import org.oxoo2a.sim4da.*;
import org.junit.jupiter.api.Test;
import java.util.Random;

public class Bankhaus {

    static final int N = 5;
    static final int START_BALANCE = 1000;
    static final int RUNTIME_SECONDS = 10; // Laufzeitbegrenzung der Simulation

    record Transfer(int amount) implements Message {} // Basisnachricht: Überweisung
    record Tick() implements Message {}               // stösst nächste eigene Überweisung an

    static class BankNode extends Node {

        private int balance = START_BALANCE;
        private final Random random = new Random();

        BankNode(int id) { super(String.valueOf(id)); }

        @Override
        protected void engage() {
            send(new Tick(), nodeName());
            while (!Thread.currentThread().isInterrupted()) {
                ReceivedMessage rm = receive();
                if (rm == null) break; 
                switch (rm.message()) {
                    case Transfer(int amount) -> balance += amount; // Geldeingang
                    case Tick t -> {
                        sleep(random.nextInt(50, 300)); // zufällige Wartezeit
                        transferToRandomNode();
                        send(new Tick(), nodeName());
                    }
                    default -> throw new IllegalStateException("Unerwartete Nachricht");
                }
            }
            System.out.printf("Prozess %s: Endsaldo %d%n", nodeName(), balance);
        }

        // Zufälligen Betrag 0 < b <= balance an zufälligen anderen Prozess
        // Konto sofort reduzieren, Nachricht mit Latenz zustellen.
        private void transferToRandomNode() {
            if (balance <= 0) return;
            int amount = random.nextInt(1, balance + 1);
            int receiver;
            do { receiver = random.nextInt(N); } while (receiver == Integer.parseInt(nodeName()));
            balance -= amount;
            sendWithLatency(new Transfer(amount), String.valueOf(receiver));
        }

        // Nachrichtenlatenz zufällig zwischen 100 und 1000 ms
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