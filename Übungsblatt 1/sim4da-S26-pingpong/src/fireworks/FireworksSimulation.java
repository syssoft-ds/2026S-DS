package fireworks;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;



public class FireworksSimulation {

    record Token(int round, boolean fired) implements Message {}
    record Rocket(int sender, int round)   implements Message {}
    record Stop()                          implements Message {}

    // Hilfsmethode: Name eines Knotens aus seiner ID
    static String nameOf(int id) { return "P" + id; }

    // Ein Ringknoten: hält Token, entscheidet über Raketen, zählt Runden und Raketen
    static class RingNode extends Node {
        private final int id, n, k;
        private double p;
        volatile int rocketsSent = 0, rocketsReceived = 0, rounds = 0;
        final List<Long> roundNanos = new ArrayList<>();

        RingNode(int id, int n, double p0, int k) {
            super(nameOf(id));
            this.id = id; this.n = n; this.p = p0; this.k = k;
        }

        // Hilfsmethode: Token ggf. mit Rakete senden, dann weitergeben
        private void holdTokenAndForward(boolean firedAlready, int round) {
            boolean fired = firedAlready;
            if (ThreadLocalRandom.current().nextDouble() < p) {
                rocketsSent++;
                fired = true;
                broadcast(new Rocket(id, round));
            }
            p /= 2.0;
            send(new Token(round, fired), nameOf((id + 1) % n));
        }

        // Hauptlogik: Token empfangen, ggf. Rakete senden, Token weitergeben
        @Override
        protected void engage() {
            int quiet = 0;
            long lastRoundStart = 0;
            if (id == 0) {
                lastRoundStart = System.nanoTime();
                holdTokenAndForward(false, 1);
            }
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Rocket(int sender, int r) -> rocketsReceived++;
                    case Stop s -> { return; }
                    case Token(int round, boolean fired) -> {
                        if (id == 0) {
                            long now = System.nanoTime();
                            roundNanos.add(now - lastRoundStart);
                            lastRoundStart = now;
                            rounds++;
                            quiet = fired ? 0 : quiet + 1;
                            if (quiet >= k) { broadcast(new Stop()); return; }
                            holdTokenAndForward(false, round + 1);
                        } else {
                            holdTokenAndForward(fired, round);
                        }
                    }
                    default -> throw new IllegalStateException(
                            "Unerwartete Nachricht: " + rm.message());
                }
            }
        }
    }

    // Hauptmethode: Parameter einlesen, Knoten erstellen, Simulation starten, Statistik ausgeben
    public static void main(String[] args) {
        int n     = (args.length > 0) ? Integer.parseInt(args[0]) : 8;
        double p0 = (args.length > 1) ? Double.parseDouble(args[1]) : 1.0;
        int k     = (args.length > 2) ? Integer.parseInt(args[2]) : 3;
        boolean csv = (args.length > 3) && args[3].equalsIgnoreCase("csv");

        Simulator simulator = Simulator.getInstance();
        RingNode[] nodes = new RingNode[n];
        for (int i = 0; i < n; i++) nodes[i] = new RingNode(i, n, p0, k);

        long t0 = System.nanoTime();
        simulator.simulate();
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        simulator.shutdown();

        int rocketsTotal = 0, receivedTotal = 0;
        for (RingNode nd : nodes) {
            rocketsTotal += nd.rocketsSent;
            receivedTotal += nd.rocketsReceived;
        }
        List<Long> rt = nodes[0].roundNanos;
        double minMs = Double.MAX_VALUE, maxMs = 0, sumMs = 0;
        for (long ns : rt) {
            double ms = ns / 1_000_000.0;
            minMs = Math.min(minMs, ms); maxMs = Math.max(maxMs, ms); sumMs += ms;
        }
        double meanMs = rt.isEmpty() ? 0 : sumMs / rt.size();
        if (rt.isEmpty()) minMs = 0;

        if (csv) {
            // Eine Zeile: n,rounds,rocketsSent,rocketsReceived,min,mean,max,wall
            System.out.printf(Locale.US, "%d,%d,%d,%d,%.4f,%.4f,%.4f,%d%n",
                n, nodes[0].rounds, rocketsTotal, receivedTotal,
                minMs, meanMs, maxMs, wallMs);
        } else {
            System.out.println("=== Aufgabe 3: simuliertes Feuerwerk (sim4da) ===");
            System.out.printf(Locale.US, "n=%d  p0=%.3f  k=%d%n", n, p0, k);
            System.out.printf(Locale.US, "Token-Runden gesamt:        %d%n", nodes[0].rounds);
            System.out.printf(Locale.US, "Gesendete Raketen gesamt:   %d%n", rocketsTotal);
            System.out.printf(Locale.US, "Empfangene Raketen gesamt:  %d%n", receivedTotal);
            System.out.printf(Locale.US, "Rundenzeit min/mittel/max:  %.4f / %.4f / %.4f ms%n",
                              minMs, meanMs, maxMs);
            System.out.printf(Locale.US, "Simulationsdauer (wall):    %d ms%n", wallMs);
        }
    }
}