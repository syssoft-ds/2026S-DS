package bankhaus;

import bankhaus.Messages.ChannelReport;
import bankhaus.Messages.NaiveReport;
import bankhaus.Messages.NaiveRequest;
import bankhaus.Messages.SnapshotRequest;
import bankhaus.Messages.StateReport;
import bankhaus.Messages.StopTransfers;
import bankhaus.Messages.Terminate;
import bankhaus.Messages.Tick;
import bankhaus.Messages.Transfer;
import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ein Kontoprozess P_i des Bankhauses (Aufgabe 1) mit der Empfaengerseite des
 * Einfaerbeverfahrens (Aufgabe 2).
 *
 * <h2>Anwendung</h2>
 * Der Prozess verwaltet einen ganzzahligen Saldo. Ein Taktgeber ({@link Tick},
 * als verzoegerte Selbstnachricht realisiert) loest nach einer kurzen zufaelligen
 * Wartezeit eine Ueberweisung aus: ein zufaelliger Betrag {@code 0 < b <= balance}
 * geht an einen zufaellig gewaehlten anderen Prozess. Der Sender belastet sein
 * Konto <em>sofort</em>, der Empfaenger schreibt gut, wenn die Nachricht ankommt.
 *
 * <h2>Farben</h2>
 * {@link #color} ist die Anzahl der bereits aufgenommenen Schnappschuesse. Bezueglich
 * der laufenden Runde {@code k} heisst der Prozess <em>weiss</em>, solange
 * {@code color < k}, und <em>schwarz</em>, sobald {@code color >= k}. Jede
 * Basisnachricht traegt die Farbe ihres Senders zum Sendezeitpunkt mit
 * ({@link Transfer#senderColor()}) — genau das ist die "Einfaerbung der Nachricht".
 *
 * <h2>Nebenlaeufigkeit</h2>
 * Saldo, Farbe und Zaehler werden ausschliesslich vom Knoten-Thread in der
 * {@code engage()}-Schleife veraendert. Ueberweisungen werden nicht von einem
 * Hilfsthread ausgeloest, sondern von der Selbstnachricht {@link Tick} — dadurch
 * ist der lokale Zustand ohne jede Sperre konsistent, und "Zustand sichern"
 * ist automatisch atomar bezueglich Senden und Empfangen.
 */
public class AccountNode extends Node {

    /** Global eindeutige Kennungen fuer Ueberweisungen, ueber alle Knoten hinweg. */
    private static final AtomicLong TRANSFER_IDS = new AtomicLong();
    /** Zaehlt die Basisnachrichten eines Laufs (nur Statistik, Aufgabe 3.3). */
    private static final AtomicLong TRANSFERS_SENT = new AtomicLong();

    static void resetCounters() {
        TRANSFER_IDS.set(0);
        TRANSFERS_SENT.set(0);
    }

    static long transfersSent() {
        return TRANSFERS_SENT.get();
    }

    private final List<String> peers;          // alle anderen Konten
    private final String coordinator;
    private final Config cfg;
    private final DelayedLinks links;
    private final Random rnd;

    // --- lokaler Zustand, ausschliesslich vom eigenen Knoten-Thread beruehrt ---
    private int balance;
    private int color = 0;
    private final Map<String, Integer> sentCount = new HashMap<>();
    private final Map<String, Integer> receivedCount = new HashMap<>();
    private boolean transferring = true;

    public AccountNode(String name, List<String> allAccounts, String coordinator,
                       Config cfg, DelayedLinks links, long seed) {
        super(name);
        this.peers = new ArrayList<>(allAccounts);
        this.peers.remove(name);
        this.coordinator = coordinator;
        this.cfg = cfg;
        this.links = links;
        this.rnd = new Random(seed);
        this.balance = cfg.initialBalance();
    }

    @Override
    protected void engage() {
        scheduleTick();
        while (true) {
            ReceivedMessage rm = receive();
            if (rm == null) return;               // Simulation wurde beendet
            switch (rm.message()) {
                case Tick ignored -> onTick();
                case Transfer t -> onTransfer(rm.sender(), t);
                case SnapshotRequest(int c) -> takeSnapshotsUpTo(c);
                case NaiveRequest(int id) -> transmit(new NaiveReport(id, nodeName(), balance), coordinator);
                case StopTransfers ignored -> transferring = false;
                case Terminate ignored -> { return; }
                default -> throw new IllegalStateException(
                        "Unerwartete Nachricht bei " + nodeName() + ": " + rm.message());
            }
        }
    }

    // ------------------------------------------------------------------
    // Anwendung (Aufgabe 1)
    // ------------------------------------------------------------------

    private void onTick() {
        if (!transferring) return;               // nach StopTransfers keine neuen Ueberweisungen
        doTransfer();
        scheduleTick();
    }

    private void doTransfer() {
        if (balance <= 0) return;                // nichts zu ueberweisen
        String receiver = peers.get(rnd.nextInt(peers.size()));
        int amount = 1 + rnd.nextInt(Math.min(balance, cfg.maxTransfer()));

        // Sendeereignis: Konto sofort belasten, dann die Basisnachricht auf den Weg
        // bringen. Sie traegt die aktuelle Farbe des Senders.
        balance -= amount;
        sentCount.merge(receiver, 1, Integer::sum);
        TRANSFERS_SENT.incrementAndGet();
        transmit(new Transfer(TRANSFER_IDS.incrementAndGet(), amount, color), receiver);
    }

    // ------------------------------------------------------------------
    // Schnappschuss (Aufgabe 2): Empfaengerseite des Einfaerbeverfahrens
    // ------------------------------------------------------------------

    /**
     * Empfang einer Basisnachricht. Hier stecken die beiden interessanten Faelle
     * der Nachrichten-Einfaerbung.
     */
    private void onTransfer(String sender, Transfer t) {

        // Fall 1 — "schwarze Nachricht trifft weissen Prozess" (Nachricht aus der Zukunft).
        // Der Sender hatte seinen Zustand schon gemeldet, als er dieses Geld abschickte;
        // der Betrag steckt also noch in seinem gemeldeten Saldo. Wuerden wir ihn jetzt
        // unserem Saldo gutschreiben und erst danach unseren Zustand melden, waere das
        // Geld doppelt erfasst und der Schnitt inkonsistent (ein Empfangsereignis waere
        // im Schnitt, das zugehoerige Sendeereignis nicht).
        // => Vor der Verarbeitung einfaerben und den Zustand sichern.
        if (t.senderColor() > color) {
            takeSnapshotsUpTo(t.senderColor());
        }

        // Fall 2 — "weisse Nachricht trifft schwarzen Prozess".
        // Der Sender hat sie vor seinem Schnitt abgeschickt (sein gemeldeter Saldo ist
        // bereits belastet), wir empfangen sie nach unserem Schnitt (unser gemeldeter
        // Saldo enthaelt sie nicht). Sie war zum Schnittzeitpunkt unterwegs und bildet
        // damit den Zustand des Kanals sender -> this. Wir melden sie nach.
        // (Die Schleife ist Vorsorge fuer den Fall mehrerer gleichzeitig offener Runden;
        //  im hier realisierten Ablauf laeuft immer hoechstens eine Runde, also c == color.)
        for (int c = t.senderColor() + 1; c <= color; c++) {
            transmit(new ChannelReport(c, sender, nodeName(), t.id(), t.amount()), coordinator);
        }

        // Fall 3 — gleiche Farbe (weiss/weiss oder schwarz/schwarz): nichts Besonderes.
        // weiss/weiss: vom Sender abgezogen, uns gutgeschrieben, beides vor dem Schnitt.
        // schwarz/schwarz: der Betrag steckt im gemeldeten Saldo des Senders, unser
        //                  gemeldeter Saldo kennt ihn nicht — ebenfalls genau einmal gezaehlt.
        receivedCount.merge(sender, 1, Integer::sum);
        balance += t.amount();
    }

    /**
     * Faerbt den Prozess auf {@code targetColor} ein und meldet fuer jede dabei
     * uebersprungene Runde den lokalen Zustand an den Koordinator.
     *
     * <p>Der gemeldete Zustand ist der Saldo <em>vor</em> Verarbeitung der
     * ausloesenden Nachricht, zusammen mit den kumulierten Sende- und
     * Empfangszaehlern. Aus diesen Zaehlern kann der Koordinator ausrechnen,
     * wie viele Basisnachrichten pro Kanal noch unterwegs sind, und weiss damit,
     * wann der Schnappschuss vollstaendig ist.
     *
     * <p>Ist {@code targetColor <= color}, war der Prozess bereits eingefaerbt
     * (z. B. durch eine schwarze Basisnachricht, die der Kontrollnachricht des
     * Koordinators zuvorgekommen ist) — die Schleife laeuft dann nicht.
     */
    private void takeSnapshotsUpTo(int targetColor) {
        while (color < targetColor) {
            color++;
            transmit(new StateReport(color, nodeName(), balance, sentCount, receivedCount), coordinator);
        }
    }

    // ------------------------------------------------------------------
    // Infrastruktur
    // ------------------------------------------------------------------

    /** Sendet mit zufaelliger Uebertragungsverzoegerung. */
    private void transmit(Message m, String to) {
        links.after(randomLatency(), () -> send(m, to));
    }

    /** Plant den naechsten Ueberweisungstakt als verzoegerte Selbstnachricht. */
    private void scheduleTick() {
        int delay = cfg.minTickMs() + rnd.nextInt(cfg.maxTickMs() - cfg.minTickMs() + 1);
        links.after(delay, () -> send(new Tick(), nodeName()));
    }

    private int randomLatency() {
        return cfg.minLatencyMs() + rnd.nextInt(cfg.maxLatencyMs() - cfg.minLatencyMs() + 1);
    }
}
