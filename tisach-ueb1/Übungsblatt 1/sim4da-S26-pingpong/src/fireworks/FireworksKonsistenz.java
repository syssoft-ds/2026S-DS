package fireworks;

import org.oxoo2a.sim4da.Message;
import org.oxoo2a.sim4da.Node;
import org.oxoo2a.sim4da.ReceivedMessage;
import org.oxoo2a.sim4da.Simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;


public class FireworksKonsistenz {

    
    record Token(int round, boolean fired, int seq, int ts) implements Message {}
    record Rocket(int sender, int round, int ts)            implements Message {}
    record Stop(int ts)                                     implements Message {}

    static String nameOf(int id) { return "P" + id; }

    static final AtomicInteger k1Violations = new AtomicInteger(0);
    static final AtomicInteger k3Violations = new AtomicInteger(0);


    enum Mode { DETECT, AVOID }

    // K1: Token-Seq muss monoton steigen (kein veraltetes zweites Token)
    static class RingNode extends Node {
        private final int id, n, k;
        private double p;
        private final double lossProb, reorderProb;
        private final Mode mode;

        private int clock = 0;              // Lamport-Uhr (K3)
        private int lastSeqSeen = -1;       // K1
        private int lastDeliveredTs = -1;   // K3

        // K3-AVOID: alle noch nicht ausgelieferten Raketen in sortierter Reihenfolge nach ts (und Sender-ID als Tiebreaker)
        private final PriorityQueue<Rocket> ordered = new PriorityQueue<>(
            (a, b) -> a.ts() != b.ts() ? Integer.compare(a.ts(), b.ts())
                                       : Integer.compare(a.sender(), b.sender()));
        // K3-AVOID: merken, bis zu welchem ts von jedem Sender bereits eine Nachricht geliefert wurde (stabil = von jedem >= ts gesehen)
        private final java.util.HashMap<Integer,Integer> lastTsFrom =
            new java.util.HashMap<>();
        // Umordnungs-Störung: kurzzeitig zurückgehaltene Raketen
        private final List<Rocket> held = new ArrayList<>();

        
        volatile int rocketsSent = 0, rocketsReceived = 0, rounds = 0;

        // Konstruktor mit Parametern für Verlust- und Umordnungswahrscheinlichkeit sowie Modus
        RingNode(int id, int n, double p0, int k,
                 double lossProb, double reorderProb, Mode mode) {
            super(nameOf(id));
            this.id = id; this.n = n; this.p = p0; this.k = k;
            this.lossProb = lossProb; this.reorderProb = reorderProb; this.mode = mode;
        }

        private int tick() { return ++clock; }
        private void update(int ts) { clock = Math.max(clock, ts) + 1; }

        // Eine Rakete an die Anwendung ausliefern und K3 prüfen
        private void deliver(Rocket r) {
            if (mode == Mode.AVOID) {
                // K3-AVOID: Rakete in sortierter Reihenfolge aufnehmen, dann alle stabilen ausliefern
                ordered.add(r);
                lastTsFrom.merge(r.sender(), r.ts(), Math::max);
                drainStable();
            } else {
                checkOrder(r);                        
            }
        }

        // K3-AVOID: Alle am Kopf der Warteschlange liegenden Raketen prüfen, ob sie stabil sind (von allen Sendern >= ts gesehen) und dann ausliefern
        private void drainStable() {
            while (!ordered.isEmpty()) {
                Rocket head = ordered.peek();
                // Stabilität prüfen: von jedem Sender >= ts gesehen?
                boolean stable = true;
                for (int s = 0; s < n; s++) {
                    if (s == id) continue;
                    int seen = lastTsFrom.getOrDefault(s, -1);
                    if (seen < head.ts()) { stable = false; break; }
                }
                if (!stable) break;                   // warten auf mehr Nachrichten
                checkOrder(ordered.poll());
            }
        }

        // K3-DETCT: Prüfen, ob die Rakete in der richtigen Reihenfolge ankommt (ts >= zuletzt ausgeliefertem ts), sonst K3-Verletzung melden
        private void checkOrder(Rocket r) {
            if (r.ts() < lastDeliveredTs) {
                k3Violations.incrementAndGet();
                System.out.printf(Locale.US,
                    "[K3-VERLETZUNG] %s: Rakete von P%d (ts=%d) nach bereits "
                    + "ausgeliefertem ts=%d%n",
                    nameOf(id), r.sender(), r.ts(), lastDeliveredTs);
            }
            lastDeliveredTs = Math.max(lastDeliveredTs, r.ts());
            rocketsReceived++;
        }

        // Empfangene Rakete behandeln: Verlust, dann evtl. Umordnung.
        private void onRocket(Rocket rk) {
            update(rk.ts());
            if (ThreadLocalRandom.current().nextDouble() < lossProb)
                return;                               // K2: verloren
            // K3-DETCT: evtl. Umordnung -> kurzzeitig zurückhalten, dann entweder diese oder zuvor zurückgehaltene ausliefern
            if (ThreadLocalRandom.current().nextDouble() < reorderProb) {
                held.add(rk);                         // spaeter ausliefern
            } else {
                deliver(rk);
                // K3-DETCT: wenn gerade eine Rakete ausgeliefert wurde, dann auch alle zurückgehaltenen (die jetzt umgeordnet ankommen würden) in sortierter Reihenfolge ausliefern
                if (!held.isEmpty()) {
                    List<Rocket> copy = new ArrayList<>(held);
                    held.clear();
                    for (Rocket h : copy) deliver(h);
                }
            }
        }

        private void flushHeld() {
            for (Rocket h : held) deliver(h);
            held.clear();
            // K3-AVOID: alle noch in der Warteschlange liegenden Raketen ausliefern (die jetzt umgeordnet ankommen würden)
            while (!ordered.isEmpty()) checkOrder(ordered.poll());
        }

        // K1: Prüfen, ob die Sequenznummer des Tokens monoton steigt, sonst K1-Verletzung melden und Token verwerfen
        private boolean acceptToken(int seq) {
            if (seq < lastSeqSeen) {
                k1Violations.incrementAndGet();
                System.out.printf(Locale.US,
                    "[K1-VERLETZUNG] %s: Token seq=%d < zuletzt gesehenem %d "
                    + "-> veraltetes zweites Token, verworfen%n",
                    nameOf(id), seq, lastSeqSeen);
                return false;
            }
            lastSeqSeen = seq;
            return true;
        }

        // Token halten, evtl. Rakete abfeuern, dann Token weitergeben
        private void holdTokenAndForward(boolean firedAlready, int round, int seq) {
            boolean fired = firedAlready;
            if (ThreadLocalRandom.current().nextDouble() < p) {
                rocketsSent++;
                fired = true;
                broadcast(new Rocket(id, round, tick()));
            }
            p /= 2.0;
            send(new Token(round, fired, seq, tick()), nameOf((id + 1) % n));
        }

        // Hauptschleife: Token empfangen und entsprechend behandeln, bis Stop-Nachricht oder Kanalende
        @Override
        protected void engage() {
            int quiet = 0;
            if (id == 0) {
                lastSeqSeen = 0;
                holdTokenAndForward(false, 1, 1);
            }
            while (true) {
                ReceivedMessage rm = receive();
                if (rm == null) { flushHeld(); return; }
                switch (rm.message()) {
                    case Rocket rk -> onRocket(rk);
                    case Stop st -> { update(st.ts()); flushHeld(); return; }
                    case Token(int round, boolean fired, int seq, int ts) -> {
                        update(ts);
                        if (!acceptToken(seq)) break;
                        if (id == 0) {
                            rounds++;
                            quiet = fired ? 0 : quiet + 1;
                            if (quiet >= k) {
                                broadcast(new Stop(tick()));
                                flushHeld();
                                return;
                            }
                            holdTokenAndForward(false, round + 1, seq + 1);
                        } else {
                            holdTokenAndForward(fired, round, seq);
                        }
                    }
                    default -> throw new IllegalStateException(
                            "Unerwartet: " + rm.message());
                }
            }
        }
    }

    
    public static void main(String[] args) {
        int n         = (args.length > 0) ? Integer.parseInt(args[0]) : 8;
        double p0     = (args.length > 1) ? Double.parseDouble(args[1]) : 1.0;
        int k         = (args.length > 2) ? Integer.parseInt(args[2]) : 3;
        double loss   = (args.length > 3) ? Double.parseDouble(args[3]) / 100.0 : 0.0;
        double reord  = (args.length > 4) ? Double.parseDouble(args[4]) / 100.0 : 0.0;
        Mode mode     = (args.length > 5 && args[5].equalsIgnoreCase("avoid"))
                        ? Mode.AVOID : Mode.DETECT;

        System.out.println("=== Aufgabe 4: Konsistenz (sim4da) ===");
        System.out.printf(Locale.US,
            "n=%d  p0=%.2f  k=%d  Verlust=%.0f%%  Umordnung=%.0f%%  Modus=%s%n",
            n, p0, k, loss * 100, reord * 100, mode);

        Simulator simulator = Simulator.getInstance();
        RingNode[] nodes = new RingNode[n];
        for (int i = 0; i < n; i++)
            nodes[i] = new RingNode(i, n, p0, k, loss, reord, mode);
        simulator.simulate();
        simulator.shutdown();

        int sent = 0, recv = 0;
        for (RingNode nd : nodes) { sent += nd.rocketsSent; recv += nd.rocketsReceived; }
        double deliveryPct = sent == 0 ? 100.0 : 100.0 * recv / (sent * (n - 1));

        System.out.println("\n--- Ergebnis ---");
        System.out.printf(Locale.US, "Token-Runden:             %d%n", nodes[0].rounds);
        System.out.printf(Locale.US, "Raketen gesendet:         %d%n", sent);
        System.out.printf(Locale.US, "Raketen ausgeliefert:     %d  (erwartet %d "
            + "ohne Verlust)%n", recv, sent * (n - 1));
        System.out.printf(Locale.US, "Zustellrate (K2):         %.1f%%%n", deliveryPct);
        System.out.printf(Locale.US, "K1-Verletzungen gemeldet: %d%n", k1Violations.get());
        System.out.printf(Locale.US, "K3-Verletzungen gemeldet: %d%n", k3Violations.get());

        System.out.println("\n--- Interpretation ---");
        if (loss == 0.0 && reord == 0.0) {
            System.out.println("Ungestoert: Simulator stellt zuverlaessig und FIFO zu");
            System.out.println("-> keine Meldungen. Inkonsistenz aus Aufg.1/2 kommt vom");
            System.out.println("Kanal (UDP/WLAN), nicht vom Algorithmus.");
        } else {
            if (loss > 0)
                System.out.printf(Locale.US, "Verlust -> Zustellrate %.1f%% < 100%%: "
                    + "K2 (Agreement) verletzt.%n", deliveryPct);
            if (reord > 0 && mode == Mode.DETECT)
                System.out.println("Umordnung + DETECT -> K3-Verletzungen werden gemeldet.");
            if (reord > 0 && mode == Mode.AVOID)
                System.out.println("Umordnung + AVOID -> geordnete Auslieferung per Lamport-"
                    + "Queue vermeidet K3-Verletzungen.");
        }
    }
}