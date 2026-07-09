package tokenring;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Small wrapper around {@link DatagramSocket}.

 * It keeps the first exercise focused on sending and receiving datagrams.
 * A later protocol layer can replace the text payload with encoded token-ring
 * messages.
 */
public final class UdpEndpoint implements AutoCloseable {
    private static final int MAX_DATAGRAM_BYTES = 1024;

    private final DatagramSocket socket;

    public UdpEndpoint(int localPort) throws IOException {
        socket = new DatagramSocket(localPort);
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void sendText(String text, InetSocketAddress destination) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(payload, payload.length, destination);
        socket.send(packet);
    }

    /**
     * Blocks until one datagram arrives.
     */
    public ReceivedDatagram receiveText() throws IOException {
        byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        String text = new String(
                packet.getData(),
                packet.getOffset(),
                packet.getLength(),
                StandardCharsets.UTF_8);
        InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
        return new ReceivedDatagram(text, sender);
    }

    @Override
    public void close() {
        socket.close();
    }
}
