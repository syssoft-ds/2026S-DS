package bankhaus;

import bankhaus.Results.RunResult;
import org.oxoo2a.sim4da.RandomValues;
import org.oxoo2a.sim4da.SimulationBehavior;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Baut die Simulation auf, laesst sie laufen und gibt die Messwerte heraus.
 * Mehrere Laeufe im selben JVM sind moeglich: {@code Simulator.shutdown()} setzt
 * den Framework-Zustand (Knotenregister, Verteilungsfunktion) zurueck.
 */
public final class BankhausRun {

    public static final String COORDINATOR = "Coordinator";

    private BankhausRun() {}

    public static RunResult run(Config cfg) {
        Simulator simulator = Simulator.getInstance();
        simulator.disableLogging();          // vor der Knotenerzeugung: kein Logfile-I/O

        // FIFO-Zustellung abschalten: der Empfaenger zieht eine zufaellige Nachricht
        // aus seiner Warteschlange statt der aeltesten.
        SimulationBehavior.setMessageQueueSelectionDistributionFunction(RandomValues.getUniformDistribution());

        AccountNode.resetCounters();

        try (DelayedLinks links = new DelayedLinks(2)) {
            List<String> accounts = new ArrayList<>(cfg.n());
            for (int i = 0; i < cfg.n(); i++) accounts.add("P" + i);

            for (int i = 0; i < cfg.n(); i++) {
                new AccountNode(accounts.get(i), accounts, COORDINATOR, cfg, links, cfg.seed() + i);
            }
            SnapshotCoordinator coordinator =
                    new SnapshotCoordinator(COORDINATOR, accounts, cfg, links, cfg.seed() + 10_000);

            // Notbremse: haengt ein Lauf (z. B. wegen eines Fehlers in einem Knoten),
            // beendet der Wachhund die Simulation von aussen. Simulator.stop() darf
            // ausdruecklich nicht aus einem Knoten heraus aufgerufen werden.
            AtomicBoolean finished = new AtomicBoolean(false);
            Thread watchdog = Thread.ofPlatform().daemon().name("watchdog").start(() -> {
                try {
                    Thread.sleep(timeoutMs(cfg));
                } catch (InterruptedException e) {
                    return;                  // regulaeres Ende
                }
                if (!finished.get()) {
                    System.err.println("Watchdog: Simulation ueberschreitet das Zeitlimit, stoppe.");
                    simulator.stop();
                }
            });

            simulator.simulate();            // laeuft, bis alle Knoten engage() verlassen haben
            finished.set(true);
            watchdog.interrupt();

            return coordinator.result();
        } finally {
            simulator.shutdown();            // Knotenregister + Verteilungsfunktion zuruecksetzen
        }
    }

    private static long timeoutMs(Config cfg) {
        long expected = cfg.warmupMs()
                + (long) cfg.snapshotRounds() * (cfg.betweenSnapshotsMs() + 8L * cfg.maxLatencyMs())
                + cfg.drainMs();
        return 4 * expected + 10_000;
    }
}
