package pingpong;

/**
 * Collects and prints consistency violations detected by the simulation.
 */
public final class ConsistencyMonitor {
    private int warnings;

    public synchronized void report(String nodeName, String reason, String details) {
        warnings++;
        if (details == null || details.isBlank()) {
            System.out.printf("CONSISTENCY_WARNING node=%s reason=%s%n", nodeName, reason);
        } else {
            System.out.printf("CONSISTENCY_WARNING node=%s reason=%s %s%n", nodeName, reason, details);
        }
    }

    public synchronized int warnings() {
        return warnings;
    }
}
