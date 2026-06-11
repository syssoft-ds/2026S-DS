package tokenring;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class RingNode {
    private static final String TOKEN = "TOKEN";
    private static final String STOP = "STOP";
    private static final String FIREWORK = "FIREWORK";

    public static void main(String[] args) throws IOException {
        if (args.length < 10) {
            printUsage();
            return;
        }

        String nodeName = args[0];
        int nodeIndex = Integer.parseInt(args[1]);
        int nodeCount = Integer.parseInt(args[2]);
        int localPort = Integer.parseInt(args[3]);
        String nextHost = args[4];
        int nextPort = Integer.parseInt(args[5]);
        String multicastGroup = args[6];
        int multicastPort = Integer.parseInt(args[7]);
        double probability = Double.parseDouble(args[8]);
        int maxSilentRounds = Integer.parseInt(args[9]);
        boolean startsWithToken = args.length >= 11 && args[10].equals("start");

        InetSocketAddress nextNode = new InetSocketAddress(nextHost, nextPort);
        Random random = new Random();
        RoundStats roundStats = new RoundStats();
        AtomicBoolean multicastStopReceived = new AtomicBoolean(false);

        try (UdpEndpoint endpoint = new UdpEndpoint(localPort);
             MulticastSocket multicastSocket = openMulticastSocket(multicastGroup, multicastPort)) {

            startMulticastListener(nodeName, multicastSocket, endpoint, multicastStopReceived);

            System.out.printf("%s listening on port %d, next node is %s%n",
                    nodeName, endpoint.localPort(), nextNode);

            if (startsWithToken) {
                TokenMessage firstRound = new TokenMessage(1, 0, 0, false, 0, System.nanoTime());
                probability = processAndForward(firstRound, probability, random, nodeName,
                        multicastGroup, multicastPort, endpoint, nextNode);
            }

            try {
                while (true) {
                    ReceivedDatagram datagram = endpoint.receiveText();

                    if (datagram.text().equals(STOP)) {
                        if (nodeIndex == 0) {
                            sendMulticast(multicastGroup, multicastPort, STOP);
                        } else {
                            endpoint.sendText(STOP, nextNode);
                        }
                        System.out.printf("%s stopped%n", nodeName);
                        return;
                    }

                    if (!datagram.text().startsWith(TOKEN + "|")) {
                        System.out.printf("%s ignored \"%s\" from %s%n",
                                nodeName, datagram.text(), datagram.sender());
                        continue;
                    }

                    TokenMessage token = TokenMessage.parse(datagram.text());

                    // Erste Node hat den Token bekommen -> log alle Daten aus dem Token und starte neue Runde
                    // Zusätzlich Check ob k Runden still waren
                    if (nodeIndex == 0 && token.hops() >= nodeCount) {
                        long roundTimeNanos = System.nanoTime() - token.startNanos();
                        roundStats.add(roundTimeNanos);

                        int silentRounds = token.firedThisRound() ? 0 : token.silentRounds() + 1;
                        System.out.printf("%s completed round %d, silentRounds=%d%n",
                                nodeName, token.round(), silentRounds);

                        if (silentRounds >= maxSilentRounds) {
                            endpoint.sendText(STOP, nextNode);
                            printResult(nodeCount, token.round(), token.multicasts(), roundStats);
                            return;
                        }

                        TokenMessage nextRound = new TokenMessage(
                                token.round() + 1,
                                0,
                                silentRounds,
                                false,
                                token.multicasts(),
                                System.nanoTime());
                        probability = processAndForward(nextRound, probability, random, nodeName,
                                multicastGroup, multicastPort, endpoint, nextNode);
                    } else {
                        probability = processAndForward(token, probability, random, nodeName,
                                multicastGroup, multicastPort, endpoint, nextNode);
                    }
                }
            } catch (IOException exception) {
                if (multicastStopReceived.get()) {
                    System.out.printf("%s stopped%n", nodeName);
                    return;
                }
                throw exception;
            }
        }
    }

    private static double processAndForward(TokenMessage token,
                                            double probability,
                                            Random random,
                                            String nodeName,
                                            String multicastGroup,
                                            int multicastPort,
                                            UdpEndpoint endpoint,
                                            InetSocketAddress nextNode) throws IOException {
        boolean fires = random.nextDouble() < probability;
        boolean firedThisRound = token.firedThisRound();
        int multicasts = token.multicasts();

        if (fires) {
            sendMulticast(multicastGroup, multicastPort, FIREWORK + "|" + nodeName + "|" + token.round());
            firedThisRound = true;
            multicasts++;
            System.out.printf("%s fired in round %d%n", nodeName, token.round());
        }

        TokenMessage updatedToken = new TokenMessage(
                token.round(),
                token.hops() + 1,
                token.silentRounds(),
                firedThisRound,
                multicasts,
                token.startNanos());

        endpoint.sendText(updatedToken.encode(), nextNode);
        return probability / 2.0;
    }

    @SuppressWarnings("deprecation")
    private static MulticastSocket openMulticastSocket(String multicastGroup, int multicastPort) throws IOException {
        MulticastSocket socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(multicastPort));
        socket.joinGroup(InetAddress.getByName(multicastGroup));
        return socket;
    }

    private static void startMulticastListener(String nodeName,
                                               MulticastSocket socket,
                                               UdpEndpoint endpoint,
                                               AtomicBoolean multicastStopReceived) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                            StandardCharsets.UTF_8);

                    // Hier wird auch der Stop multicast der ersten Node abgefangen damit alle java prozesse vollständig
                    // terminieren und die ports wieder freigegeben werden für den nächsten Tokenring
                    if (text.startsWith(FIREWORK + "|")) {
                        System.out.printf("%s received multicast %s%n", nodeName, text);
                    } else if (text.equals(STOP)) {
                        multicastStopReceived.set(true);
                        endpoint.close();
                        socket.close();
                        return;
                    }
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void sendMulticast(String multicastGroup, int multicastPort, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        InetAddress group = InetAddress.getByName(multicastGroup);
        DatagramPacket packet = new DatagramPacket(payload, payload.length, group, multicastPort);

        try (MulticastSocket socket = new MulticastSocket()) {
            socket.send(packet);
        }
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

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  <nodeName> <nodeIndex> <nodeCount> <localPort> <nextHost> <nextPort> " +
                "<multicastGroup> <multicastPort> <initialProbability> <k> [start]");
    }

    private record TokenMessage(int round,
                                int hops,
                                int silentRounds,
                                boolean firedThisRound,
                                int multicasts,
                                long startNanos) {
        String encode() {
            return TOKEN + "|" + round + "|" + hops + "|" + silentRounds + "|" +
                    firedThisRound + "|" + multicasts + "|" + startNanos;
        }

        static TokenMessage parse(String text) {
            String[] parts = text.split("\\|");
            if (parts.length != 7 || !parts[0].equals(TOKEN)) {
                throw new IllegalArgumentException("Invalid token: " + text);
            }
            return new TokenMessage(
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Boolean.parseBoolean(parts[4]),
                    Integer.parseInt(parts[5]),
                    Long.parseLong(parts[6]));
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
