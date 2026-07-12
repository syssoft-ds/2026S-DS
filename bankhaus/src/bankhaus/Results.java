package bankhaus;

import java.util.List;

/** Messwerte eines Laufs, die der Koordinator nach {@code simulate()} herausgibt. */
public final class Results {

    private Results() {}

    /**
     * Ergebnis eines konsistenten Schnappschusses (Aufgabe 2).
     *
     * @param color           Nummer der Schnappschuss-Runde
     * @param balanceSum      Summe aller gemeldeten Kontostaende
     * @param channelSum      Summe aller Ueberweisungen, die zum Schnitt unterwegs waren
     * @param inTransit       Anzahl dieser Ueberweisungen
     * @param controlMessages Kontrollnachrichten dieser Runde: n Anfragen + n Zustandsmeldungen
     *                        + eine Nachmeldung je unterwegs befindlicher Ueberweisung
     * @param durationMs      Zeit vom Multicast bis zur Vollstaendigkeit des Schnappschusses
     */
    public record SnapshotStat(int color,
                               int balanceSum,
                               int channelSum,
                               int inTransit,
                               int controlMessages,
                               long durationMs) {

        public int total() {
            return balanceSum + channelSum;
        }

        public boolean consistent(int expectedTotal) {
            return total() == expectedTotal;
        }
    }

    /**
     * Ergebnis eines naiven Schnappschusses (Aufgabe 3.2): nur Kontostaende, keine Kanaele.
     *
     * @param delta Abweichung von der Gesamtsumme S. Negativ: Geld "verschwindet"
     *              (eine Ueberweisung war unterwegs und wurde nirgends gezaehlt).
     *              Positiv: Geld "entsteht" (der Sender wurde vor seinem Sendeereignis,
     *              der Empfaenger nach seinem Empfangsereignis abgefragt — ein
     *              Empfangsereignis im Schnitt ohne das zugehoerige Sendeereignis).
     */
    public record NaiveStat(int id, int balanceSum, int delta, int controlMessages) {}

    /** Alles, was ein Lauf liefert. */
    public record RunResult(Config cfg,
                            List<SnapshotStat> snapshots,
                            List<NaiveStat> naiveSnapshots,
                            long baseMessages) {}
}
