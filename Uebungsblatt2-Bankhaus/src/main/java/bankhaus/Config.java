package bankhaus;

/**
 * Simulation parameters.
 *
 * @param n                number of fully connected processes, P0 is the coordinator
 * @param startBalance     initial balance per process, S = n * startBalance
 * @param transfersPerNode budget of transfers each process may initiate
 * @param seedTransfers    transfers each process fires at startup
 * @param minLatencyMs     minimum message latency (sleep between debit and send)
 * @param maxLatencyMs     maximum message latency
 * @param maxTransfer      upper bound per transfer (still b <= balance)
 * @param snapshotEvery    coordinator triggers a snapshot after this many
 *                         transfers it processed itself
 * @param mode             COLORED, NAIVE or ALTERNATING
 * @param seed             base seed for reproducible randomness
 */
public record Config(int n, int startBalance, int transfersPerNode, int seedTransfers,
                     int minLatencyMs, int maxLatencyMs, int maxTransfer,
                     int snapshotEvery, Mode mode, long seed) {

    public enum Mode { COLORED, NAIVE, ALTERNATING }

    public static Config standard(int n, Mode mode) {
        return new Config(n, 1000, 400, 3, 20, 120, 250, 8, mode, 42L);
    }

    /** Total sum S that every consistent snapshot must reproduce. */
    public long totalSum() {
        return (long) n * startBalance;
    }
}
