package bankhaus;

/**
 * Alle Stellschrauben eines Simulationslaufs an einer Stelle.
 *
 * @param n                  Anzahl der Kontoprozesse (vollstaendig vernetzt)
 * @param initialBalance     Startsaldo jedes Kontos; die Gesamtsumme ist {@code S = n * initialBalance}
 * @param minLatencyMs       untere Schranke der zufaelligen Uebertragungsverzoegerung
 * @param maxLatencyMs       obere Schranke der zufaelligen Uebertragungsverzoegerung
 * @param minTickMs          untere Schranke der zufaelligen Wartezeit zwischen zwei Ueberweisungen
 * @param maxTickMs          obere Schranke der zufaelligen Wartezeit zwischen zwei Ueberweisungen
 * @param maxTransfer        obere Schranke fuer den ueberwiesenen Betrag
 * @param snapshotRounds     Anzahl der Schnappschuss-Runden (je ein naiver und ein konsistenter)
 * @param warmupMs           Vorlauf, bevor der erste Schnappschuss ausgeloest wird
 * @param betweenSnapshotsMs Pause zwischen zwei Schnappschuss-Runden
 * @param seed               Startwert der Zufallsgeneratoren (Reproduzierbarkeit)
 * @param verbose            Wenn {@code true}, gibt der Koordinator den vollstaendigen globalen Zustand aus
 */
public record Config(int n,
                     int initialBalance,
                     int minLatencyMs,
                     int maxLatencyMs,
                     int minTickMs,
                     int maxTickMs,
                     int maxTransfer,
                     int snapshotRounds,
                     int warmupMs,
                     int betweenSnapshotsMs,
                     long seed,
                     boolean verbose) {

    /** Die zu Beginn bekannte Gesamtsumme S. */
    public int totalMoney() {
        return n * initialBalance;
    }

    /**
     * Zeit, die nach {@code StopTransfers} gewartet wird, damit alle noch
     * unterwegs befindlichen Ueberweisungen zugestellt werden koennen.
     */
    public int drainMs() {
        return 4 * maxLatencyMs + maxTickMs;
    }

    /** Standardkonfiguration fuer einen einzelnen, gut beobachtbaren Lauf. */
    public static Config demo() {
        return new Config(5, 1000, 40, 200, 30, 90, 250, 3, 600, 700, 42L, true);
    }

    public Config withN(int n) {
        return new Config(n, initialBalance, minLatencyMs, maxLatencyMs, minTickMs, maxTickMs,
                maxTransfer, snapshotRounds, warmupMs, betweenSnapshotsMs, seed, verbose);
    }

    public Config withTick(int minTickMs, int maxTickMs) {
        return new Config(n, initialBalance, minLatencyMs, maxLatencyMs, minTickMs, maxTickMs,
                maxTransfer, snapshotRounds, warmupMs, betweenSnapshotsMs, seed, verbose);
    }

    public Config withRounds(int snapshotRounds, int betweenSnapshotsMs) {
        return new Config(n, initialBalance, minLatencyMs, maxLatencyMs, minTickMs, maxTickMs,
                maxTransfer, snapshotRounds, warmupMs, betweenSnapshotsMs, seed, verbose);
    }

    public Config withSeed(long seed) {
        return new Config(n, initialBalance, minLatencyMs, maxLatencyMs, minTickMs, maxTickMs,
                maxTransfer, snapshotRounds, warmupMs, betweenSnapshotsMs, seed, verbose);
    }

    public Config withVerbose(boolean verbose) {
        return new Config(n, initialBalance, minLatencyMs, maxLatencyMs, minTickMs, maxTickMs,
                maxTransfer, snapshotRounds, warmupMs, betweenSnapshotsMs, seed, verbose);
    }
}
