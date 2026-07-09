package tokenring;

import java.net.InetSocketAddress;

/**
 * One UDP datagram decoded as text together with the sender address.
 */
public record ReceivedDatagram(String text, InetSocketAddress sender) {}
