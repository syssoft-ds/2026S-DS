package bankhaus;

import bankhaus.Results.NaiveStat;
import bankhaus.Results.RunResult;
import bankhaus.Results.SnapshotStat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aufgabe 3: experimenteller Konsistenznachweis.
 *
 * <p><b>Teil 1 — Messreihe.</b> n und Ueberweisungsfrequenz werden variiert. Jeder Lauf
 * nimmt waehrend des laufenden Betriebs mehrfach je einen naiven und einen konsistenten
 * Schnappschuss auf. Geprueft wird, ob Konten + Kanaele = S gilt; erfasst werden u. a.
 * die Kontrollnachrichten pro Schnappschuss.
 * Ergebnis: {@code results/schnappschuesse.csv}
 *
 * <p><b>Teil 2 — Der naive Schnappschuss taeuscht in beide Richtungen.</b> Bei hoher
 * Ueberweisungsfrequenz sind stets viele Ueberweisungen unterwegs; der naive Schnitt
 * verliert dann fast immer Geld. Mit seltenen Ueberweisungen ist meist hoechstens eine
 * Ueberweisung unterwegs, und man sieht beide Fehler getrennt: Geld verschwindet
 * (Sendeereignis im Schnitt, Empfangsereignis nicht erfasst) oder Geld entsteht
 * (Empfangsereignis im Schnitt, Sendeereignis nicht — eine "Nachricht aus der Zukunft").
 * Ergebnis: {@code results/naiv.csv}
 */
public class ConsistencyExperiments {

    private static final int[] NS = {3, 5, 8, 12};
    /** Obere Schranke der Wartezeit zwischen zwei Ueberweisungen: klein = hohe Frequenz. */
    private static final int[] TICKS = {40, 80, 160};
    private static final int REPETITIONS = 2;

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Path.of("results"));
        messreihe();
        naiveDemonstration();
    }

    // ------------------------------------------------------------------
    // Teil 1: n und Ueberweisungsfrequenz variieren
    // ------------------------------------------------------------------

    private static void messreihe() throws IOException {
        List<String> csv = new ArrayList<>();
        csv.add("n,tickMaxMs,seed,runde,S,konten,kanaele,gesamt,konsistent,unterwegs,"
                + "kontrollnachrichten,dauerMs,naivSumme,naivAbweichung,basisnachrichten");

        int consistentOk = 0, consistentTotal = 0;
        int naiveWrong = 0, naiveLoss = 0, naiveGain = 0, naiveTotal = 0;
        Map<Integer, List<SnapshotStat>> byN = new LinkedHashMap<>();

        System.out.println("=== Teil 1: Messreihe ueber n und Ueberweisungsfrequenz ===");
        for (int n : NS) {
            for (int tick : TICKS) {
                for (int rep = 0; rep < REPETITIONS; rep++) {
                    long seed = 1000L * n + 10L * tick + rep;
                    Config cfg = Config.demo()
                            .withN(n)
                            .withTick(Math.max(10, tick / 3), tick)
                            .withSeed(seed)
                            .withVerbose(false);

                    RunResult r = BankhausRun.run(cfg);
                    if (r == null || r.snapshots().size() != cfg.snapshotRounds()) {
                        throw new IllegalStateException("Lauf unvollstaendig: n=" + n + " tick=" + tick);
                    }

                    for (int i = 0; i < r.snapshots().size(); i++) {
                        SnapshotStat s = r.snapshots().get(i);
                        NaiveStat ns = r.naiveSnapshots().get(i);
                        boolean ok = s.consistent(cfg.totalMoney());

                        consistentTotal++;
                        if (ok) consistentOk++;
                        naiveTotal++;
                        if (ns.delta() != 0) {
                            naiveWrong++;
                            if (ns.delta() < 0) naiveLoss++; else naiveGain++;
                        }
                        byN.computeIfAbsent(n, k -> new ArrayList<>()).add(s);

                        csv.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d,%b,%d,%d,%d,%d,%d,%d",
                                n, tick, seed, s.color(), cfg.totalMoney(), s.balanceSum(), s.channelSum(),
                                s.total(), ok, s.inTransit(), s.controlMessages(), s.durationMs(),
                                ns.balanceSum(), ns.delta(), r.baseMessages()));
                    }
                    System.out.printf("  n=%2d tick<=%3dms seed=%4d -> %d Schnappschuesse, alle konsistent: %b, "
                                    + "im Mittel %.1f Ueberweisungen unterwegs%n",
                            n, tick, seed, r.snapshots().size(),
                            r.snapshots().stream().allMatch(s -> s.consistent(cfg.totalMoney())),
                            r.snapshots().stream().mapToInt(SnapshotStat::inTransit).average().orElse(0));
                }
            }
        }

        write(Path.of("results", "schnappschuesse.csv"), csv);

        System.out.println("\n---------------- Auswertung Teil 1 ----------------");
        System.out.printf("Konsistente Schnappschuesse: %d/%d erfuellen Konten + Kanaele = S%n",
                consistentOk, consistentTotal);
        System.out.printf("Naive Schnappschuesse      : %d/%d weichen von S ab "
                        + "(%d mal Geld verschwunden, %d mal Geld entstanden)%n",
                naiveWrong, naiveTotal, naiveLoss, naiveGain);

        System.out.println("\nKontrollnachrichten pro konsistentem Schnappschuss (Mittel ueber alle Frequenzen):");
        System.out.printf("  %-4s %-12s %-16s %-18s %s%n", "n", "2n (fix)", "Nachmeldungen", "Kontrollnachr.", "Dauer/ms");
        for (var e : byN.entrySet()) {
            int n = e.getKey();
            double ctrl = e.getValue().stream().mapToInt(SnapshotStat::controlMessages).average().orElse(0);
            double transit = e.getValue().stream().mapToInt(SnapshotStat::inTransit).average().orElse(0);
            double dur = e.getValue().stream().mapToLong(SnapshotStat::durationMs).average().orElse(0);
            System.out.printf("  %-4d %-12d %-16.1f %-18.1f %.0f%n", n, 2 * n, transit, ctrl, dur);
        }
    }

    // ------------------------------------------------------------------
    // Teil 2: der naive Schnappschuss verliert und erfindet Geld
    // ------------------------------------------------------------------

    private static void naiveDemonstration() throws IOException {
        System.out.println("\n=== Teil 2: naiver Schnappschuss bei seltenen Ueberweisungen ===");
        List<String> csv = new ArrayList<>();
        csv.add("seed,schnappschuss,S,naivSumme,naivAbweichung,konsistentGesamt,unterwegs");

        int loss = 0, gain = 0, exact = 0;
        for (long seed = 1; seed <= 6; seed++) {
            Config cfg = Config.demo()
                    .withN(3)
                    .withTick(400, 900)          // seltene Ueberweisungen
                    .withRounds(20, 60)          // viele Schnappschuesse hintereinander
                    .withSeed(seed * 7919)
                    .withVerbose(false);

            RunResult r = BankhausRun.run(cfg);
            for (int i = 0; i < r.snapshots().size(); i++) {
                NaiveStat ns = r.naiveSnapshots().get(i);
                SnapshotStat s = r.snapshots().get(i);
                if (ns.delta() < 0) loss++;
                else if (ns.delta() > 0) gain++;
                else exact++;
                if (!s.consistent(cfg.totalMoney())) {
                    throw new IllegalStateException("Konsistenter Schnappschuss verletzt die Invariante!");
                }
                csv.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d",
                        cfg.seed(), s.color(), cfg.totalMoney(), ns.balanceSum(), ns.delta(),
                        s.total(), s.inTransit()));
            }
        }
        write(Path.of("results", "naiv.csv"), csv);

        int total = loss + gain + exact;
        System.out.println("\n---------------- Auswertung Teil 2 ----------------");
        System.out.printf("%d naive Schnappschuesse: %d zufaellig korrekt, %d mal Geld verschwunden, "
                + "%d mal Geld entstanden%n", total, exact, loss, gain);
        System.out.println("Alle konsistenten Schnappschuesse derselben Laeufe waren korrekt (Konten + Kanaele = S).");
        System.out.printf("%nCSV geschrieben: %s, %s%n",
                Path.of("results", "schnappschuesse.csv").toAbsolutePath(),
                Path.of("results", "naiv.csv").toAbsolutePath());
    }

    private static void write(Path out, List<String> lines) {
        try {
            Files.write(out, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
