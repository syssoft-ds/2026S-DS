package pingpong;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.Locale;
import java.util.Random;

public class FireworkSimulation {
    private static final double DEFAULT_INITIAL_PROBABILITY = 0.5;
    private static final int DEFAULT_SILENT_ROUNDS = 3;
    private static final long DEFAULT_SEED = 2026L;

    record Token(int round,
                 int hops,
                 int silentRounds,
                 boolean firedThisRound,
                 int multicasts,
                 long startNanos) implements Message {}

    record Firework(String nodeName, int round) implements Message {}
    record Stop() implements Message {}

    static class FireworkNode extends Node {
        private final int index;
        private final int nodeCount;
        private final String nextNode;
        private final int maxSilentRounds;
        private final Random random;
        private final boolean startsWithToken;
        private final boolean verbose;
        private final RoundStats roundStats;
        private double probability;

        FireworkNode(int index,
                     int nodeCount,
                     double initialProbability,
                     int maxSilentRounds,
                     long seed,
                     boolean startsWithToken,
                     boolean verbose,
                     RoundStats roundStats) {
            super(FireworkSimulation.nodeName(index));
            this.index = index;
            this.nodeCount = nodeCount;
            this.nextNode = FireworkSimulation.nodeName((index + 1) % nodeCount);
            this.probability = initialProbability;
            this.maxSilentRounds = maxSilentRounds;
            this.random = new Random(seed + index);
            this.startsWithToken = startsWithToken;
            this.verbose = verbose;
            this.roundStats = roundStats;
        }

        @Override
        protected void engage() {
            if (startsWithToken) {
                processAndForward(new Token(1, 0, 0, false, 0, System.nanoTime()));
            }

            while (true) {
                ReceivedMessage received = receive();
                if (received == null) {
                    return;
                }

                switch (received.message()) {
                    case Token token -> {
                        if (handleToken(token)) {
                            return;
                        }
                    }
                    case Firework firework -> handleFirework(firework);
                    case Stop stop -> {
                        if (verbose) {
                            System.out.printf("%s stopped%n", nodeName());
                        }
                        return;
                    }
                    default -> throw new IllegalStateException(
                            "Unexpected message at " + nodeName() + ": " + received.message());
                }
            }
        }

        private boolean handleToken(Token token) {
            if (index == 0 && token.hops() >= nodeCount) {
                return completeRound(token);
            }

            processAndForward(token);
            return false;
        }

        private boolean completeRound(Token token) {
            roundStats.add(System.nanoTime() - token.startNanos());
            int silentRounds = token.firedThisRound() ? 0 : token.silentRounds() + 1;

            if (verbose) {
                System.out.printf("%s completed round %d, silentRounds=%d%n",
                        nodeName(), token.round(), silentRounds);
            }

            if (silentRounds >= maxSilentRounds) {
                broadcast(new Stop());
                printResult(nodeCount, token.round(), token.multicasts(), roundStats);
                return true;
            }

            processAndForward(new Token(
                    token.round() + 1,
                    0,
                    silentRounds,
                    false,
                    token.multicasts(),
                    System.nanoTime()));
            return false;
        }

        private void processAndForward(Token token) {
            boolean fires = random.nextDouble() < probability;
            boolean firedThisRound = token.firedThisRound();
            int multicasts = token.multicasts();

            if (fires) {
                broadcast(new Firework(nodeName(), token.round()));
                firedThisRound = true;
                multicasts++;
                if (verbose) {
                    System.out.printf("%s fired in round %d%n", nodeName(), token.round());
                }
            }

            probability /= 2.0;
            send(new Token(
                    token.round(),
                    token.hops() + 1,
                    token.silentRounds(),
                    firedThisRound,
                    multicasts,
                    token.startNanos()), nextNode);
        }

        private void handleFirework(Firework firework) {
            if (verbose) {
                System.out.printf("%s received firework from %s in round %d%n",
                        nodeName(), firework.nodeName(), firework.round());
            }
        }
    }

    public static void main(String[] args) {
        SimulationConfig config = SimulationConfig.parse(args);

        Simulator simulator = Simulator.getInstance();
        if (!config.verbose()) {
            simulator.disableLogging();
        }

        RoundStats roundStats = new RoundStats();
        for (int i = 0; i < config.nodeCount(); i++) {
            new FireworkNode(
                    i,
                    config.nodeCount(),
                    config.initialProbability(),
                    config.maxSilentRounds(),
                    config.seed(),
                    i == 0,
                    config.verbose(),
                    roundStats);
        }

        simulator.simulate();
        simulator.shutdown();
    }

    private static void printResult(int nodeCount, int rounds, int multicasts, RoundStats roundStats) {
        System.out.printf(Locale.US, "RESULT n=%d rounds=%d multicasts=%d minMs=%.3f avgMs=%.3f maxMs=%.3f%n",
                nodeCount,
                rounds,
                multicasts,
                roundStats.minMillis(),
                roundStats.avgMillis(),
                roundStats.maxMillis());
    }

    private static String nodeName(int index) {
        return "N" + index;
    }

    private record SimulationConfig(int nodeCount,
                                    double initialProbability,
                                    int maxSilentRounds,
                                    long seed,
                                    boolean verbose) {
        static SimulationConfig parse(String[] args) {
            int nodeCount = args.length > 0 ? Integer.parseInt(args[0]) : 8;
            double initialProbability = args.length > 1
                    ? Double.parseDouble(args[1])
                    : DEFAULT_INITIAL_PROBABILITY;
            int maxSilentRounds = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_SILENT_ROUNDS;
            long seed = args.length > 3 ? Long.parseLong(args[3]) : DEFAULT_SEED;
            boolean verbose = args.length > 4 && Boolean.parseBoolean(args[4]);

            if (nodeCount < 1) {
                throw new IllegalArgumentException("nodeCount must be at least 1");
            }
            if (initialProbability < 0.0 || initialProbability > 1.0) {
                throw new IllegalArgumentException("initialProbability must be between 0.0 and 1.0");
            }
            if (maxSilentRounds < 1) {
                throw new IllegalArgumentException("maxSilentRounds must be at least 1");
            }

            return new SimulationConfig(nodeCount, initialProbability, maxSilentRounds, seed, verbose);
        }
    }

    private static final class RoundStats {
        private int count;
        private long totalNanos;
        private long minNanos = Long.MAX_VALUE;
        private long maxNanos;

        void add(long nanos) {
            count++;
            totalNanos += nanos;
            minNanos = Math.min(minNanos, nanos);
            maxNanos = Math.max(maxNanos, nanos);
        }

        double minMillis() {
            return toMillis(count == 0 ? 0 : minNanos);
        }

        double avgMillis() {
            return toMillis(count == 0 ? 0 : totalNanos / count);
        }

        double maxMillis() {
            return toMillis(maxNanos);
        }

        private double toMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
