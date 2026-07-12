package bankhaus;

import org.oxoo2a.sim4da.Message;

import java.util.Map;

/**
 * Alle Nachrichtentypen der Bankhaus-Simulation. Jede Nachricht ist ein
 * {@code record} und implementiert das Marker-Interface {@link Message}.
 *
 * <p>Begrifflich zerfallen die Nachrichten in drei Gruppen:
 * <ul>
 *   <li><b>Basisnachrichten</b> der Anwendung: {@link Transfer}. Nur diese
 *       transportieren Geld und nur diese bilden die Kanalzustände, die der
 *       Schnappschuss erfassen muss.</li>
 *   <li><b>Kontrollnachrichten</b> des Schnappschuss-Algorithmus:
 *       {@link SnapshotRequest}, {@link StateReport}, {@link ChannelReport}
 *       sowie {@link NaiveRequest}/{@link NaiveReport} fuer den naiven
 *       Vergleichs-Schnappschuss.</li>
 *   <li><b>Ablaufsteuerung</b> der Simulation: {@link Tick} (Selbstnachricht
 *       als Taktgeber), {@link StopTransfers}, {@link Terminate}.</li>
 * </ul>
 */
public final class Messages {

    private Messages() {}

    // ------------------------------------------------------------------
    // Basisnachricht (Aufgabe 1)
    // ------------------------------------------------------------------

    /**
     * Ueberweisung von {@code amount} Geldeinheiten.
     *
     * @param id          eindeutige Kennung, damit eine konkrete Ueberweisung im
     *                    Kanalzustand wiedererkennbar ist
     * @param amount      der ueberwiesene Betrag, {@code 0 < amount <= balance} des Senders
     * @param senderColor die <em>Farbe des Senders zum Sendezeitpunkt</em>, d. h. die Anzahl
     *                    der Schnappschuesse, die der Sender bereits aufgenommen hatte.
     *                    Das ist die Einfaerbung der Basisnachricht: bezueglich Runde
     *                    {@code k} ist die Nachricht weiss, falls {@code senderColor < k},
     *                    und schwarz, falls {@code senderColor >= k}.
     */
    public record Transfer(long id, int amount, int senderColor) implements Message {}

    // ------------------------------------------------------------------
    // Ablaufsteuerung
    // ------------------------------------------------------------------

    /** Selbstnachricht: loest die naechste Ueberweisung aus (Taktgeber). */
    public record Tick() implements Message {}

    /** Der Koordinator beendet die Ueberweisungsphase. */
    public record StopTransfers() implements Message {}

    /** Der Koordinator beendet die Simulation. */
    public record Terminate() implements Message {}

    // ------------------------------------------------------------------
    // Schnappschuss, Variante (a): Koordinator / Einfaerbeverfahren (Aufgabe 2)
    // ------------------------------------------------------------------

    /**
     * Kontrollnachricht {@code (state?, Farbe)} des Koordinators, per Multicast an
     * die Gruppe. {@code color} ist die Nummer der Schnappschuss-Runde und damit
     * die "schwarze" Farbe dieser Runde.
     */
    public record SnapshotRequest(int color) implements Message {}

    /**
     * Antwort eines Prozesses auf {@link SnapshotRequest}: sein lokaler Zustand
     * im Moment des Einfaerbens.
     *
     * @param sentCount     kumulierte Anzahl der bis zum Schnitt an den jeweiligen
     *                      Empfaenger gesendeten Ueberweisungen
     * @param receivedCount kumulierte Anzahl der bis zum Schnitt vom jeweiligen
     *                      Sender empfangenen Ueberweisungen
     */
    public record StateReport(int color,
                              String node,
                              int balance,
                              Map<String, Integer> sentCount,
                              Map<String, Integer> receivedCount) implements Message {
        // Defensivkopie: der Prozess arbeitet auf seinen Zaehler-Maps weiter,
        // die gemeldete Momentaufnahme darf sich davon nicht mehr aendern.
        public StateReport {
            sentCount = Map.copyOf(sentCount);
            receivedCount = Map.copyOf(receivedCount);
        }
    }

    /**
     * Nachmeldung einer weissen Basisnachricht, die ein bereits schwarzer Prozess
     * empfangen hat. Sie war zum Schnittzeitpunkt unterwegs und bildet damit den
     * Zustand des Kanals {@code from -> to}.
     */
    public record ChannelReport(int color, String from, String to, long id, int amount) implements Message {}

    // ------------------------------------------------------------------
    // Naiver Schnappschuss (Aufgabe 3.2)
    // ------------------------------------------------------------------

    /** Der Koordinator fragt nur den Kontostand ab und ignoriert die Kanaele. */
    public record NaiveRequest(int id) implements Message {}

    /** Antwort auf {@link NaiveRequest}: der Kontostand zum Antwortzeitpunkt. */
    public record NaiveReport(int id, String node, int balance) implements Message {}
}
