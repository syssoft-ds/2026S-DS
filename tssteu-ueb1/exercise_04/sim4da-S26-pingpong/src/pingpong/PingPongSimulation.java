package pingpong;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

public class PingPongSimulation {

    record Ping(int round) implements Message {}
    record Pong(int round) implements Message {}
    record Stop()          implements Message {}

    static class Pinger extends Node {
        private final String partner;
        private final int rounds;

        Pinger(String name, String partner, int rounds) {
            super(name);
            this.partner = partner;
            this.rounds = rounds;
        }

        @Override
        protected void engage() {
            send(new Ping(1), partner);
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Pong(int r) -> {
                        System.out.printf("%s got Pong(%d) from %s%n",
                                          nodeName(), r, rm.sender());
                        if (r >= rounds) {
                            send(new Stop(), partner);
                            return;
                        }
                        send(new Ping(r + 1), partner);
                    }
                    default -> throw new IllegalStateException(
                            "Unexpected message at Pinger: " + rm.message());
                }
            }
        }
    }

    static class Ponger extends Node {
        Ponger(String name) { super(name); }

        @Override
        protected void engage() {
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                switch (rm.message()) {
                    case Ping(int r) -> {
                        System.out.printf("%s got Ping(%d) from %s%n",
                                          nodeName(), r, rm.sender());
                        send(new Pong(r), rm.sender());
                    }
                    case Stop s -> {
                        System.out.printf("%s stopping%n", nodeName());
                        return;
                    }
                    default -> throw new IllegalStateException(
                            "Unexpected message at Ponger: " + rm.message());
                }
            }
        }
    }

    public static void main(String[] args) {
        int rounds = (args.length > 0) ? Integer.parseInt(args[0]) : 10;

        Simulator simulator = Simulator.getInstance();
        new Pinger("Ping", "Pong", rounds);
        new Ponger("Pong");
        simulator.simulate();
        simulator.shutdown();
    }
}
