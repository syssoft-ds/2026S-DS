package bankhaus;

import bankhaus.Results.NaiveStat;
import bankhaus.Results.RunResult;
import bankhaus.Results.SnapshotStat;

/**
 * Einstiegspunkt fuer Aufgabe 1 + 2: ein Lauf des Bankhauses, waehrend dessen der
 * Koordinator mehrfach einen naiven und einen konsistenten Schnappschuss aufnimmt
 * und den vollstaendigen globalen Zustand ausgibt.
 *
 * <pre>
 *   ./gradlew run                # Standardkonfiguration
 *   ./gradlew run --args="8"     # 8 Kontoprozesse
 * </pre>
 */
public class BankhausSimulation {

    public static void main(String[] args) {
        final Config cfg = args.length > 0
                ? Config.demo().withN(Integer.parseInt(args[0]))
                : Config.demo();

        System.out.printf("Bankhaus: n = %d Prozesse, Startsaldo %d, S = %d%n",
                cfg.n(), cfg.initialBalance(), cfg.totalMoney());
        System.out.printf("Latenz %d-%d ms, Ueberweisungstakt %d-%d ms, FIFO-Zustellung abgeschaltet%n",
                cfg.minLatencyMs(), cfg.maxLatencyMs(), cfg.minTickMs(), cfg.maxTickMs());

        RunResult r = BankhausRun.run(cfg);

        System.out.printf("%n=== Zusammenfassung (%d Basisnachrichten insgesamt) ===%n", r.baseMessages());
        System.out.println("  Naive Schnappschuesse (nur Konten):");
        for (NaiveStat ns : r.naiveSnapshots()) {
            System.out.printf("    #%d  Summe %6d  Abweichung %+5d  %s%n",
                    ns.id(), ns.balanceSum(), ns.delta(),
                    ns.delta() < 0 ? "<- Geld verschwunden (Kanalzustand fehlt)"
                            : ns.delta() > 0 ? "<- Geld entstanden (inkonsistenter Schnitt)"
                            : "");
        }
        System.out.println("  Konsistente Schnappschuesse (Konten + Kanaele):");
        for (SnapshotStat s : r.snapshots()) {
            System.out.printf("    #%d  Konten %6d + Kanaele %5d = %6d  (S = %d)  %s%n",
                    s.color(), s.balanceSum(), s.channelSum(), s.total(), cfg.totalMoney(),
                    s.consistent(cfg.totalMoney()) ? "OK" : "FEHLER");
        }

        boolean allOk = r.snapshots().stream().allMatch(s -> s.consistent(cfg.totalMoney()));
        System.out.println(allOk
                ? "\nAlle konsistenten Schnappschuesse erfuellen die Invariante Konten + Kanaele = S."
                : "\nFEHLER: mindestens ein Schnappschuss verletzt die Invariante.");
    }
}
