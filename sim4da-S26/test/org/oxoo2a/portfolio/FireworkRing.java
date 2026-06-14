package org.oxoo2a.portfolio;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.SimulationBehavior;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Aufgabe 3 + 4: Simuliertes Feuerwerk im Token-Ring mit Konsistenzmechanismen.
 *
 * <p>Aufgabe 3: Portierung der UDP-Anwendung aus Aufgabe 1 auf sim4da.
 * n Knoten bilden einen logischen Ring. Knoten "0" ist Koordinator: er erzeugt
 * das Token (Streichholz), zaehlt Runden und stille Runden und initiiert die
 * Terminierung (STOP-Nachricht), wenn k Runden in Folge kein Knoten gezuendet
 * hat. Jeder Knoten zuendet beim Token-Empfang mit Wahrscheinlichkeit p eine
 * Rakete (broadcast) und halbiert anschliessend sein lokales p.
 *
 * <p>Aufgabe 4: Konsistenzkriterien, deren Verletzungen erkannt und gemeldet
 * (ConsistencyReport) bzw. bei aktivierter Vermeidung verhindert werden:
 *
 * <ul>
 *   <li><b>C1 Token-Integritaet:</b> Es kreist genau ein Token; jeder Knoten
 *       sieht die Rundennummern streng monoton aufsteigend (+1).
 *       Verletzungen: TOKEN_DUPLICATE (altes/dupliziertes Token),
 *       TOKEN_LOST_ROUND (Runde uebersprungen).
 *       Vermeidung: veraltete Token werden erkannt und verworfen statt
 *       weitergeleitet.</li>
 *   <li><b>C2 Vollstaendigkeit der Beobachtung:</b> Jeder Knoten beobachtet
 *       jede fremde Rakete genau einmal. Das Token (und die STOP-Nachricht)
 *       fuehrt einen kumulativen Zaehler totalFired mit; daraus kennt jeder
 *       Knoten seinen Sollwert (totalFired - eigene Zuendungen).
 *       Verletzungen: FIREWORK_LOST (bei Terminierung nicht beobachtet),
 *       FIREWORK_DUPLICATE. Vermeidung: vor der Terminierung wird die Mailbox
 *       gezielt leergelesen (Flush), bis der Sollwert erreicht ist.</li>
 *   <li><b>C3 Kausale Reihenfolge:</b> Der Broadcast einer Rakete geschieht,
 *       bevor der Zuender das Token weiterreicht — die Rakete ist damit
 *       kausal vor jedem spaeteren Token-Empfang "hinter" ihr im Ring
 *       (gleiche Runde, Zuender liegt vor dem Empfaenger) und vor jedem
 *       Token-Empfang einer spaeteren Runde. Beobachtet ein Knoten eine
 *       Rakete erst, nachdem er das kausal nachfolgende Token verarbeitet
 *       hat, ist seine Sicht kausal inkonsistent ("Rakete aus der
 *       Vergangenheit"). Verletzung: FIREWORK_STALE. Vermeidung: vor der
 *       Verarbeitung eines Tokens werden zunaechst alle kausal vorausgehenden
 *       Raketen aus der Mailbox verarbeitet (Flush bis totalFired-Stand des
 *       Tokens).</li>
 *   <li><b>C4 Terminierungskonsistenz:</b> Alle Knoten beenden die Anwendung
 *       mit derselben Sicht (Rundenzahl, Gesamtzahl Raketen). Die
 *       Terminierungsentscheidung beruht deterministisch auf dem
 *       firedInRound-Flag im Token (nicht auf asynchron eintreffenden
 *       Broadcasts); die STOP-Nachricht traegt das verbindliche Endergebnis,
 *       gegen das jeder Knoten seine lokale Sicht prueft (C2-Pruefung).</li>
 * </ul>
 *
 * <p>Alle Nachrichten tragen Lamport-Zeitstempel, damit gemeldete
 * Verletzungen global kausal eingeordnet werden koennen.
 *
 * <p>Inkonsistenzen entstehen im fehlerfreien sim4da nur bei nicht-FIFO-
 * Zustellung (zufaellige Mailbox-Auswahl via {@link SimulationBehavior});
 * zusaetzlich kann zur Demonstration von C1 ein Token-Duplikat injiziert
 * werden ({@code duplicateTokenInRound}).
 */
public class FireworkRing {

    // ---------------------------------------------------------------- Nachrichten

    /** Das Streichholz. Traegt Rundennummer, Zuendungs-Flag der laufenden
     *  Runde, kumulative Gesamtzahl gezuendeter Raketen und Lamport-Zeit. */
    public record Token(int round, boolean firedInRound, long totalFired, long lamport) implements Message {}

    /** Eine Feuerwerksrakete (Broadcast). originSeq ist eine pro Zuender
     *  fortlaufende Nummer, sodass Duplikate erkennbar sind. */
    public record Firework(int origin, int round, long originSeq, long lamport) implements Message {}

    /** Terminierungsmarker mit dem verbindlichen Endergebnis. */
    public record Stop(long totalFired, int totalRounds, long lamport) implements Message {}

    // ---------------------------------------------------------------- Konsistenz-Report

    public enum ViolationType {
        TOKEN_DUPLICATE,     // C1: altes oder dupliziertes Token empfangen
        TOKEN_LOST_ROUND,    // C1: Rundennummer uebersprungen
        FIREWORK_DUPLICATE,  // C2: Rakete mehrfach beobachtet
        FIREWORK_LOST,       // C2: Rakete bis zur Terminierung nicht beobachtet
        FIREWORK_STALE       // C3: Rakete aus einer bereits abgeschlossenen Runde
    }

    public record Violation(String node, ViolationType type, long lamport, String detail) {}

    /** Globale, threadsichere Sammlung aller gemeldeten Verletzungen eines Laufs. */
    public static final class ConsistencyReport {
        private static final Queue<Violation> violations = new ConcurrentLinkedQueue<>();

        static void report(Violation v) {
            violations.add(v);
            System.out.printf("[KONSISTENZ] Knoten %s: %s @L%d — %s%n",
                              v.node(), v.type(), v.lamport(), v.detail());
        }

        static void clear() { violations.clear(); }

        static List<Violation> snapshot() { return new ArrayList<>(violations); }
    }

    // ---------------------------------------------------------------- Ergebnis eines Laufs

    public record NodeStats(int id, long fired, long observed, long expectedObserved) {}

    public record RunResult(int n, int rounds, long totalFired, long wallMillis,
                            double minRoundMs, double avgRoundMs, double maxRoundMs,
                            List<NodeStats> nodeStats, List<Violation> violations) {

        /** Anzahl Multicast-Sendungen (eine Rakete = ein Broadcast). */
        public long multicasts() { return totalFired; }

        public long violationCount(ViolationType t) {
            return violations.stream().filter(v -> v.type() == t).count();
        }

        public String summaryLine() {
            return String.format(Locale.ROOT,
                    "SUMMARY n=%d rounds=%d multicasts=%d round_ms_min=%.3f round_ms_avg=%.3f " +
                    "round_ms_max=%.3f wall_ms=%d violations=%d",
                    n, rounds, multicasts(), minRoundMs, avgRoundMs, maxRoundMs,
                    wallMillis, violations.size());
        }

        public String csvLine() {
            return String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%d,%d",
                    n, rounds, multicasts(), minRoundMs, avgRoundMs, maxRoundMs,
                    wallMillis, violations.size());
        }

        public static final String CSV_HEADER =
                "n,rounds,multicasts,round_ms_min,round_ms_avg,round_ms_max,wall_ms,violations";
    }

    // ---------------------------------------------------------------- Konfiguration eines Laufs

    public record Config(int n, double p0, int k,
                         boolean nonFifoMailbox,   // zufaellige statt FIFO-Zustellung
                         boolean avoidance,        // Vermeidung (Flush, Token verwerfen) an/aus
                         int hopDelayMillis,       // max. zufaellige Verarbeitungszeit pro Empfang
                                                   // (modelliert unterschiedlich schnelle Knoten)
                         int duplicateTokenInRound,// Fehlerinjektion: Knoten n/2 dupliziert das Token in dieser Runde (0 = aus)
                         long seed) {
        public static Config of(int n, double p0, int k) {
            return new Config(n, p0, k, false, false, 0, 0, 42L);
        }
    }

    // ---------------------------------------------------------------- Ring-Knoten

    static class FireworkNode extends Node {
        private final Config cfg;
        private final int id;
        private final String next;
        private final Random rng;
        private final Random delayRng; // eigene Quelle, damit Zuendentscheidungen bei
                                       // gleichem Seed ueber Konfigurationen vergleichbar bleiben

        private double p;                 // lokale Zuendwahrscheinlichkeit
        private long lamport = 0;         // Lamport-Uhr
        private int lastTokenRound = 0;   // hoechste verarbeitete Runde
        private long ownFired = 0;        // selbst gezuendete Raketen (= originSeq-Zaehler)
        private long observed = 0;        // beobachtete fremde Raketen (Unikate)
        private final Set<String> seenFireworks = new HashSet<>();
        private boolean stopped = false;

        // Nur Koordinator (id 0):
        private int silentRounds = 0;
        private long totalFiredAtStop = -1;
        private long roundStartNanos;
        final List<Long> roundTimesNanos = new ArrayList<>();

        // Nach dem Lauf vom Runner ausgelesen (happens-before via join):
        NodeStats finalStats;

        FireworkNode(int id, Config cfg) {
            super(String.valueOf(id));
            this.id = id;
            this.cfg = cfg;
            this.next = String.valueOf((id + 1) % cfg.n());
            this.p = cfg.p0();
            this.rng = new Random(cfg.seed() * 1_000_003L + id);
            this.delayRng = new Random(cfg.seed() * 7_919L + id);
        }

        private long tick() { return ++lamport; }

        private void onReceive(long msgLamport) { lamport = Math.max(lamport, msgLamport) + 1; }

        private void violate(ViolationType type, String detail) {
            ConsistencyReport.report(new Violation(nodeName(), type, lamport, detail));
        }

        @Override
        protected void engage() {
            if (id == 0) {
                // Koordinator eroeffnet Runde 1 mit sich selbst als erstem Glied.
                roundStartNanos = System.nanoTime();
                processToken(new Token(1, false, 0, tick()), true);
            }
            while (!stopped) {
                // Zufaellige Verarbeitungszeit: waehrenddessen koennen sich
                // mehrere Nachrichten in der Mailbox ansammeln — erst dann
                // kann eine nicht-FIFO-Auswahl ueberhaupt umordnen.
                if (cfg.hopDelayMillis() > 0) sleep(delayRng.nextInt(cfg.hopDelayMillis() + 1));
                ReceivedMessage rm = receive();
                if (rm == null) break; // Simulation von aussen beendet
                switch (rm.message()) {
                    case Token t -> {
                        onReceive(t.lamport());
                        if (id == 0) onTokenReturned(t);
                        else processToken(t, false);
                    }
                    case Firework f -> {
                        onReceive(f.lamport());
                        handleFirework(f);
                    }
                    case Stop s -> {
                        onReceive(s.lamport());
                        handleStop(s);
                    }
                    default -> throw new IllegalStateException("Unerwartete Nachricht: " + rm.message());
                }
            }
            long expected = (totalFiredAtStop >= 0 ? totalFiredAtStop : 0) - ownFired;
            finalStats = new NodeStats(id, ownFired, observed, Math.max(expected, 0));
        }

        /** C1-Pruefung fuer Nicht-Koordinatoren; true = Token verarbeiten. */
        private boolean checkIncomingToken(Token t) {
            if (t.round() <= lastTokenRound) {
                violate(ViolationType.TOKEN_DUPLICATE,
                        "Token Runde " + t.round() + " erneut empfangen (aktuelle Runde " + lastTokenRound + ")");
                return !cfg.avoidance(); // Vermeidung: Duplikat verwerfen
            }
            if (t.round() > lastTokenRound + 1) {
                violate(ViolationType.TOKEN_LOST_ROUND,
                        "Runde " + (lastTokenRound + 1) + " uebersprungen, Token traegt Runde " + t.round());
            }
            return true;
        }

        /** Fire-Entscheidung treffen und Token weiterreichen. */
        private void processToken(Token t, boolean isRoundStart) {
            if (!isRoundStart && !checkIncomingToken(t)) return;

            // C3-Vermeidung: alle kausal vor dem Token liegenden Raketen zuerst verarbeiten.
            if (cfg.avoidance()) flushFireworks(t.totalFired());
            lastTokenRound = Math.max(lastTokenRound, t.round());

            boolean firedFlag = t.firedInRound();
            long totalFired = t.totalFired();
            if (rng.nextDouble() < p) {
                ownFired++;
                broadcast(new Firework(id, t.round(), ownFired, tick()));
                firedFlag = true;
                totalFired++;
            }
            p /= 2.0;

            Token forward = new Token(t.round(), firedFlag, totalFired, tick());
            send(forward, next);

            // Fehlerinjektion fuer C1: Knoten n/2 schickt das Token doppelt los.
            if (cfg.duplicateTokenInRound() == t.round() && id == cfg.n() / 2 && id != 0) {
                send(forward, next);
            }
        }

        /** Koordinator: Token hat den Ring vollendet — Runde abschliessen. */
        private void onTokenReturned(Token t) {
            if (t.round() != lastTokenRound) {
                // Ein Duplikat oder eine Phantom-Runde; niemals daraus eine neue Runde starten.
                violate(t.round() < lastTokenRound ? ViolationType.TOKEN_DUPLICATE
                                                   : ViolationType.TOKEN_LOST_ROUND,
                        "Tokenrueckkehr mit Runde " + t.round() + ", erwartet " + lastTokenRound);
                return;
            }
            roundTimesNanos.add(System.nanoTime() - roundStartNanos);
            silentRounds = t.firedInRound() ? 0 : silentRounds + 1;

            if (silentRounds >= cfg.k()) {
                totalFiredAtStop = t.totalFired();
                send(new Stop(t.totalFired(), t.round(), tick()), next);
            } else {
                roundStartNanos = System.nanoTime();
                processToken(new Token(t.round() + 1, false, t.totalFired(), tick()), true);
            }
        }

        /** Eine beobachtete Rakete verbuchen und auf C2/C3 pruefen. */
        private void handleFirework(Firework f) {
            String key = f.origin() + ":" + f.originSeq();
            if (!seenFireworks.add(key)) {
                violate(ViolationType.FIREWORK_DUPLICATE,
                        "Rakete " + key + " (Runde " + f.round() + ") mehrfach beobachtet");
                return;
            }
            observed++;
            // Kausal verspaetet ist eine Rakete, wenn das Token, das ihren
            // Broadcast kausal "mitgenommen" hat, hier schon verarbeitet
            // wurde: jede Rakete aus einer frueheren Runde, sowie eine Rakete
            // der aktuellen Runde von einem Knoten, der im Ring vor diesem
            // Knoten liegt (sein Broadcast geschah, bevor das Token ihn
            // Richtung dieses Knotens verliess).
            if (f.round() < lastTokenRound
                    || (f.round() == lastTokenRound && f.origin() < id)) {
                violate(ViolationType.FIREWORK_STALE,
                        "Rakete von Knoten " + f.origin() + " aus Runde " + f.round() +
                        " erst nach dem Token der Runde " + lastTokenRound + " beobachtet");
            }
        }

        /**
         * C2/C3-Vermeidung: Mailbox leeren, bis alle kausal vorausgehenden
         * Raketen (Sollwert: totalFired - eigene) beobachtet wurden. Die
         * Zustellung in die Mailbox erfolgt synchron beim Broadcast, daher
         * liegen alle erwarteten Raketen bereits in der Mailbox und der
         * Flush terminiert ohne Blockieren auf neue Nachrichten.
         */
        private void flushFireworks(long totalFired) {
            long expected = totalFired - ownFired;
            while (observed < expected) {
                ReceivedMessage rm = receive();
                if (rm == null) return;
                if (rm.message() instanceof Firework f) {
                    onReceive(f.lamport());
                    handleFirework(f);
                } else if (rm.message() instanceof Token t) {
                    // Nur ein verwaistes Duplikat kann hier auftauchen (das echte
                    // Token haelt gerade dieser Knoten bzw. es existiert in der
                    // Stop-Phase nicht mehr) — melden und verwerfen.
                    onReceive(t.lamport());
                    if (checkIncomingToken(t)) {
                        throw new IllegalStateException(
                                "Gueltiges Token waehrend Flush empfangen: " + t);
                    }
                } else {
                    throw new IllegalStateException(
                            "Unerwartete Nachricht waehrend Flush: " + rm.message());
                }
            }
        }

        /** Terminierung: lokale Sicht gegen das verbindliche Endergebnis pruefen. */
        private void handleStop(Stop s) {
            totalFiredAtStop = s.totalFired();
            long expected = s.totalFired() - ownFired;
            if (observed < expected) {
                if (cfg.avoidance()) {
                    flushFireworks(s.totalFired());
                } else {
                    violate(ViolationType.FIREWORK_LOST,
                            "Bei Terminierung nur " + observed + " von " + expected +
                            " fremden Raketen beobachtet");
                }
            }
            if (id != 0) send(new Stop(s.totalFired(), s.totalRounds(), tick()), next);
            stopped = true;
        }
    }

    // ---------------------------------------------------------------- Runner

    /** Fuehrt einen kompletten Simulationslauf aus und sammelt die Ergebnisse ein. */
    public static RunResult run(Config cfg) {
        Simulator simulator = Simulator.getInstance();
        simulator.disableLogging(); // EventLog wuerde sonst jede Sendung in eine Datei schreiben
        ConsistencyReport.clear();

        if (cfg.nonFifoMailbox()) {
            // Achtung Framework-Detail: getLong(0, queueSize-1) schneidet
            // v*(queueSize-1) ab — mit v aus [0,1) ergibt das bei Queue-Laenge 2
            // immer Index 0 (faktisch FIFO). Ein Supplier, der exakt 0.0 oder
            // 1.0 liefert, waehlt dagegen fair zwischen aeltester und neuester
            // Nachricht und erzeugt damit echte Umordnung.
            Random mailboxRng = new Random(cfg.seed());
            SimulationBehavior.setMessageQueueSelectionDistributionFunction(
                    () -> mailboxRng.nextBoolean() ? 1.0 : 0.0);
        }

        FireworkNode[] nodes = new FireworkNode[cfg.n()];
        for (int i = 0; i < cfg.n(); i++) {
            nodes[i] = new FireworkNode(i, cfg);
        }

        long wallStart = System.currentTimeMillis();
        simulator.simulate();
        long wallMillis = System.currentTimeMillis() - wallStart;

        List<Long> roundTimes = nodes[0].roundTimesNanos;
        double min = roundTimes.stream().mapToDouble(ns -> ns / 1e6).min().orElse(0);
        double avg = roundTimes.stream().mapToDouble(ns -> ns / 1e6).average().orElse(0);
        double max = roundTimes.stream().mapToDouble(ns -> ns / 1e6).max().orElse(0);

        List<NodeStats> stats = new ArrayList<>();
        long totalFired = 0;
        for (FireworkNode node : nodes) {
            stats.add(node.finalStats);
            totalFired += node.finalStats.fired();
        }

        RunResult result = new RunResult(cfg.n(), roundTimes.size(), totalFired, wallMillis,
                                         min, avg, max, stats, ConsistencyReport.snapshot());
        simulator.shutdown();
        return result;
    }
}
