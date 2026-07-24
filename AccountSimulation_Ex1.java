package org.oxoo2a.sim4da;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AccountSimulation_Ex1 {



    // Token
    record Token(int hopsLeft) implements Message {}

    // Überweisung
    record Transfer(int sender, int receiver, int amount) implements Message {}

    // Abbruch
    record Stop()          implements Message {}


    static class ProcessNode extends Node {
        private final int id;
        private int balance;
        private final int partners;
        private final boolean isCoordinator;


        ProcessNode(int id, int balance, int partners) {
            super(String.valueOf(id));
            this.id = id;
            this.balance = balance;
            this.partners = partners;
            this.isCoordinator = (id == 0);
        }

        @Override
        protected void engage() {
            if (isCoordinator) {
                new Thread(() -> {
                    while (true) {
                        sleep(1000);
                        int sender = new Random().nextInt(0, partners);
                        send(new Token(0), String.valueOf(sender));
                    }
                }).start();

                int sender = new Random().nextInt(0, partners);
                Token t = new Token(0);
                send(t, String.valueOf(sender));
            }


            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) {
                    return;
                }
                switch (rm.message()) {
                    case Token t -> handleToken();
                    case Transfer t -> handleTransfer(rm, t);
                    case Stop s -> { return; }
                    default -> { }
                }
            }
        }

        private void handleToken(){
            if (balance > 0){
                int amount = new Random().nextInt(0, balance);
                int receiver = id;
                while (receiver == id) {
                    receiver = new Random().nextInt(0 , partners);
                }
                Transfer transfer = new Transfer(id, receiver, amount);
                balance = balance-amount;


                System.out.printf("[%d]: Sende an %d %d€%n. Mein neuer Kontostand: %d%n", id, receiver, amount, balance);
                send(transfer, String.valueOf(receiver));
            }
        }

        private void handleTransfer(ReceivedMessage rm, Transfer t){
            System.out.printf("[Prozess %d]: folgende message erhalten %s %n", id, rm.message());
            if (t.receiver == id) {
                balance += t.amount;
            }
            System.out.printf("[Prozess %d]: Mein neuer Kontostand beträgt %d %n", id, balance);

        }
    }


    public static void main(String[] args) {
        Simulator simulator = Simulator.getInstance();
        List<ProcessNode> processes = new ArrayList<>();

        int n = 4;
        for (int i=0; i<n; i++) {
            processes.add(new ProcessNode(i, 1000, n));
            System.out.printf("Process %d started. %n", i);
        }


        simulator.simulate(3);
        simulator.shutdown();


    }
}