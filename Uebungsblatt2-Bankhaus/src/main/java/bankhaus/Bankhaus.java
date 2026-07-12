package bankhaus;

import org.oxoo2a.sim4da.OverwriteDistributionFunctionException;
import org.oxoo2a.sim4da.RandomValues;
import org.oxoo2a.sim4da.SimulationBehavior;
import org.oxoo2a.sim4da.Simulator;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point and the only class touching the sim4da lifecycle:
 * disable FIFO delivery, construct the nodes, simulate(seconds), shutdown.
 */
public final class Bankhaus {

    private Bankhaus() {}

    /** Receivers pick a uniformly random message from their queue (non-FIFO). */
    static void disableFifoDelivery() {
        try {
            SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                    RandomValues.getUniformDistribution());
        } catch (OverwriteDistributionFunctionException e) {
            // already set in this JVM (repeated runs in tests) — fine
        }
    }

    /** Runs one full simulation and returns the collected statistics. */
    public static Stats run(Config cfg, int seconds, Stats stats) {
        disableFifoDelivery();

        Simulator simulator = Simulator.getInstance();
        new CoordinatorNode(cfg, stats);          // P0: bank process + coordinator
        for (int i = 1; i < cfg.n(); i++) new BankNode(i, cfg);

        simulator.simulate(seconds);
        simulator.shutdown();                     // reset framework for the next run
        return stats;
    }

    /** Demo: alternating colored and naive snapshots. Args: [n] [seconds]. */
    public static void main(String[] args) throws IOException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        Config cfg = Config.standard(n, Config.Mode.ALTERNATING);
        System.out.printf("Bankhaus: n = %d, S = %d, Modus %s, %d s%n",
                cfg.n(), cfg.totalSum(), cfg.mode(), seconds);

        Stats stats = run(cfg, seconds, new Stats());

        Path csv = Path.of("stats.csv");
        stats.writeCsv(csv);
        System.out.printf("%n%d Schnappschuesse aufgezeichnet -> %s%n",
                stats.rows().size(), csv.toAbsolutePath());
    }
}
