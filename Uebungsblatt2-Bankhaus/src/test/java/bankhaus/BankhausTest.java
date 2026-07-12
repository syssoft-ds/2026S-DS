package bankhaus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Experimental consistency proofs and the statistics run (Aufgabe 3). */
class BankhausTest {

    /** Every colored snapshot taken while transfers keep running captures exactly S. */
    @Test
    void coloredSnapshotIsAlwaysConsistent() {
        Stats stats = Bankhaus.run(Config.standard(6, Config.Mode.COLORED), 15, new Stats());

        List<Stats.Row> colored = stats.rows("colored");
        assertFalse(colored.isEmpty(), "expected at least one completed colored snapshot");
        for (Stats.Row r : colored) {
            assertTrue(r.consistent(), () -> "inconsistent round " + r.round()
                    + ": captured " + r.capturedSum() + " != S = " + r.expectedSum());
        }
    }

    /** The naive snapshot (balances only, channels ignored) yields sums != S. */
    @Test
    void naiveSnapshotYieldsInconsistencies() {
        Stats stats = Bankhaus.run(Config.standard(8, Config.Mode.NAIVE), 15, new Stats());

        List<Stats.Row> naive = stats.rows("naive");
        assertFalse(naive.isEmpty(), "expected at least one completed naive snapshot");
        assertTrue(naive.stream().anyMatch(r -> !r.consistent()),
                "expected at least one inconsistent naive snapshot");
    }

    /** Vary n and transfer frequency (latency profiles), write stats.csv. */
    @Test
    void statisticsExperiment() throws IOException {
        Stats stats = new Stats();
        int[][] latencyProfiles = { {10, 60}, {40, 200} }; // fast and slow

        for (int n : new int[]{4, 8, 16}) {
            for (int[] lat : latencyProfiles) {
                Config cfg = new Config(n, 1000, 400, 3, lat[0], lat[1], 250,
                        8, Config.Mode.ALTERNATING, 42L);
                Bankhaus.run(cfg, 12, stats);
            }
        }

        assertFalse(stats.rows().isEmpty(), "statistics run should produce rows");
        stats.writeCsv(Path.of("stats.csv"));
        System.out.printf("Statistik: %d Zeilen -> stats.csv%n", stats.rows().size());
    }
}
