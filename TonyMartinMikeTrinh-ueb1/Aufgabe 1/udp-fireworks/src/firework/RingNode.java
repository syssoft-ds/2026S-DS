package firework;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ein Knoten im logischen Ring (Aufgabe 1 – "Feuerwerk an UDP-Nachrichten").
 *
 * <p>n solcher Prozesse bilden einen Ring. Ein Token ("Streichholz") kreist per
 * <b>Unicast-UDP</b> von Knoten i zu Knoten (i+1) mod n. Wer das Token hält, zündet mit
 * Wahrscheinlichkeit p eine Rakete (<b>UDP-Multicast</b> an alle) und halbiert dann sein p.
 * Knoten 0 ist Initiator: er injiziert das Token, misst Rundenzeiten, zählt Runden und
 * terminiert, wenn in k aufeinanderfolgenden Runden niemand gezündet hat.
 *
 * <p>Literatur: Ring-Koordination Coulouris §15.2; IP-Multicast Coulouris §4.4.1 /
 * Van Steen §4.4; UDP-Fehlermodell (Verlust) Coulouris §4.2.1.
 *
 * <p>Robustheit (Hybrid): Token wird bei Verlust NICHT neu gesendet (pure UDP-Semantik).
 * Stattdessen beendet sich jeder Knoten bei Leerlauf-Timeout selbst (kein Zombie).
 *
 * <p>Threads pro Knoten: (1) Token-/Hauptthread blockiert auf dem Unicast-Socket;
 * (2) Multicast-Listener behandelt READY/GO/FIRE/STOP. Geteilter Zustand ist unten markiert.
 */
public class RingNode {

    // --- Startup/Robustheit-Konstanten ---------------------------------------------------
    private static final int READY_INTERVAL_MS  = 150;    // Resend-Intervall der READY-Barriere
    private static final int STOP_BURST         = 5;      // wie oft STOP/GO multicastet wird

    // --- Konfiguration (immutable nach Konstruktion) --------------------------------------
    private final int rank;
    private final int n;
    private final int basePort;
    private final String mcAddr;
    private final int mcPort;
    private final double p0;
    private final int k;
    private final int idleTimeoutMs;
    private final int ttl;
    private final boolean verbose;
    private final int startupTimeoutMs;   // max. Wartezeit bis der Ring steht (skaliert mit n)

    // --- Netzwerk -------------------------------------------------------------------------
    private final InetAddress loopback;          // 127.0.0.1
    private final InetAddress group;             // Multicast-Gruppe
    private DatagramSocket unicast;              // empfängt Token vom Vorgänger
    private MulticastSocket mcast;               // empfängt/sendet Multicasts
    private NetworkInterface mcastIf;            // gewähltes Multicast-Interface

    // --- Geteilter Zustand zwischen Token-Thread und Multicast-Listener -------------------
    /** Shutdown-Flag; von beiden Threads gelesen/geschrieben → volatile. */
    private volatile boolean running = true;
    /** true, sobald der Ring aktiv ist (GO gesehen oder erstes Token). Steuert den Idle-Exit. */
    private volatile boolean ringActive = false;
    /** Nur Knoten 0: true während des eigenen STOP-Bursts → eigenes (per Loopback) STOP ignorieren. */
    private volatile boolean stopping = false;
    /** Vom Multicast-Listener inkrementiert, beim Shutdown gelesen → Atomic. */
    private final AtomicInteger fireworksObserved = new AtomicInteger(0);
    /** Nur Knoten 0: Menge bereits gemeldeter Ränge. Zugriff nur durch den Initiator-Thread. */
    private final Set<Integer> readyRanks = new HashSet<>();
    /** Nicht-Initiator: ausgelöst bei GO; stoppt den READY-Announcer. */
    private final CountDownLatch goLatch = new CountDownLatch(1);
    /** Idempotenter Shutdown-Guard. */
    private final Object shutdownLock = new Object();
    private boolean shutdownDone = false;

    // --- Nur Token-Thread (kein Sharing → keine Synchronisierung nötig) -------------------
    private double p;                            // aktuelle Zündwahrscheinlichkeit
    private int fireworksSent = 0;               // lokal gezündete Raketen
    private final List<Long> roundDurationsNanos = new ArrayList<>(); // Knoten 0: Rundendauern
    private int consecutiveQuietLaps = 0;        // Knoten 0: stille Runden in Folge
    private long fwTotal = 0;                     // Knoten 0: kumulierte Raketen (aus Token)
    private int completedLaps = 0;                // Knoten 0: abgeschlossene Runden

    // --- Logging (nur bei verbose) --------------------------------------------------------
    private BufferedWriter logWriter;

    private RingNode(String[] args) throws IOException {
        this.rank          = Integer.parseInt(args[0]);
        this.n             = Integer.parseInt(args[1]);
        this.basePort      = Integer.parseInt(args[2]);
        this.mcAddr        = args[3];
        this.mcPort        = Integer.parseInt(args[4]);
        this.p0            = Double.parseDouble(args[5]);
        this.k             = Integer.parseInt(args[6]);
        this.idleTimeoutMs    = (args.length > 7)  ? Integer.parseInt(args[7]) : 8000;
        this.ttl              = (args.length > 8)  ? Integer.parseInt(args[8]) : 1;
        this.verbose          = (args.length > 9)  && Boolean.parseBoolean(args[9]);
        this.startupTimeoutMs = (args.length > 10) ? Integer.parseInt(args[10]) : 30_000;
        this.p             = p0;
        this.loopback      = InetAddress.getByName("127.0.0.1");
        this.group         = InetAddress.getByName(mcAddr);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("Usage: RingNode <rank> <n> <basePort> <mcAddr> <mcPort> "
                    + "<p0> <k> [idleTimeoutMs] [ttl] [verbose]");
            System.exit(2);
        }
        new RingNode(args).run();
    }

    private void run() throws Exception {
        setupSockets();
        if (verbose) {
            Path logDir = Path.of("logs");
            Files.createDirectories(logDir);
            logWriter = Files.newBufferedWriter(logDir.resolve("node-" + rank + ".log"));
            logLine("startup rank=" + rank + " n=" + n + " p0=" + p0 + " k=" + k
                    + " mcastIf=" + (mcastIf != null ? mcastIf.getName() : "default") + " ttl=" + ttl);
        }

        Thread listener = new Thread(this::multicastListenLoop, "mcast-listener-" + rank);
        listener.setDaemon(true);
        listener.start();

        unicast.setSoTimeout(idleTimeoutMs);

        if (rank == 0) {
            runInitiator();
        } else {
            startReadyAnnouncer();
            runFollower();
        }

        shutdown();
        if (listener.isAlive()) {
            listener.join(500);
        }
    }

    // =====================================================================================
    //  Setup
    // =====================================================================================

    // Großer Empfangspuffer: bei vielen Knoten kommen READY/FIRE-Multicasts in Bursts; ein zu
    // kleiner Kernel-Puffer würde sie verwerfen und so die Startbarriere künstlich begrenzen.
    private static final int RCV_BUFFER = 1 << 20; // 1 MiB (OS deckelt ggf.)

    private void setupSockets() throws IOException {
        // Unicast-Empfänger fest an 127.0.0.1:basePort+rank (mit Retry gegen transiente Belegung
        // durch einen gerade beendenden Vorgängerprozess).
        unicast = bindUnicastWithRetry(basePort + rank);
        unicast.setReceiveBufferSize(RCV_BUFFER);

        // Multicast: mehrere lokale Prozesse teilen sich denselben Port → SO_REUSEADDR.
        mcast = new MulticastSocket(null);
        mcast.setReuseAddress(true);
        mcast.setReceiveBufferSize(RCV_BUFFER);
        mcast.bind(new InetSocketAddress(mcPort));
        mcast.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true); // eigene/lokale Pakete empfangen
        mcast.setTimeToLive(ttl);

        mcastIf = chooseMulticastInterface();
        InetSocketAddress groupSockAddr = new InetSocketAddress(group, mcPort);
        if (mcastIf != null) {
            mcast.setNetworkInterface(mcastIf);
            mcast.joinGroup(groupSockAddr, mcastIf);
        } else {
            // Letzter Ausweg: Default-Interface (ältere API).
            mcast.joinGroup(group);
        }
    }

    private DatagramSocket bindUnicastWithRetry(int port) throws IOException {
        java.net.BindException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return new DatagramSocket(new InetSocketAddress(loopback, port));
            } catch (java.net.BindException e) {
                last = e;
                sleepQuietly(200);
            }
        }
        throw last;
    }

    /**
     * Wählt ein Interface für host-lokales Multicast. Loopback wird bevorzugt (bleibt auf dem
     * Rechner); sonst das erste aktive, multicast-fähige IPv4-Interface. Alle Knoten wählen
     * deterministisch dasselbe, damit Sender und Empfänger zusammenfinden.
     */
    private static NetworkInterface chooseMulticastInterface() throws IOException {
        NetworkInterface fallback = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || !ni.supportsMulticast()) continue;
            boolean hasV4 = ni.getInterfaceAddresses().stream()
                    .anyMatch(ia -> ia.getAddress() instanceof Inet4Address);
            if (!hasV4) continue;
            if (ni.isLoopback()) return ni;          // bevorzugt: host-lokal
            if (fallback == null) fallback = ni;     // erstes brauchbares reales Interface
        }
        return fallback;
    }

    // =====================================================================================
    //  Startbarriere
    // =====================================================================================

    /** Nicht-Initiator: meldet periodisch READY (Unicast an Knoten 0), bis der Ring aktiv ist. */
    private void startReadyAnnouncer() {
        Thread t = new Thread(() -> {
            try {
                while (running && !ringActive) {
                    sendUnicast(basePort, "READY " + rank); // direkt an Knoten 0
                    if (goLatch.await(READY_INTERVAL_MS, TimeUnit.MILLISECONDS)) break;
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Socket evtl. geschlossen → Ring fährt herunter, kein Resend nötig.
            }
        }, "ready-announcer-" + rank);
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================================
    //  Initiator (rank 0)
    // =====================================================================================

    private void runInitiator() throws IOException {
        // Startbarriere: READY kommt per Unicast direkt hier an (O(n) statt O(n²) wie bei
        // Multicast-READY, das jeden Knoten flutet). Das Token-Loop läuft noch nicht, der
        // Unicast-Socket ist also frei zum Sammeln. Zugriff nur durch diesen Thread.
        readyRanks.add(rank); // sich selbst zählen
        long deadline = System.currentTimeMillis() + startupTimeoutMs;
        while (running && readyRanks.size() < n && System.currentTimeMillis() < deadline) {
            DatagramPacket pkt = receiveUnicast();   // null bei Timeout
            if (pkt == null) continue;
            String m = payload(pkt);
            if (m.startsWith("READY ")) {
                try {
                    readyRanks.add(Integer.parseInt(m.substring(6).trim()));
                } catch (NumberFormatException ignored) { /* defekt → ignorieren */ }
            }
        }
        if (readyRanks.size() < n) {
            StringBuilder missing = new StringBuilder();
            for (int r = 0; r < n; r++) if (!readyRanks.contains(r)) missing.append(r).append(',');
            System.err.println("startup_failed: ready=" + readyRanks.size() + "/" + n
                    + " missing=[" + missing + "]");
            printSummary("startup_failed");
            return;
        }

        // Ring steht: GO verkünden und Token injizieren.
        ringActive = true;
        for (int i = 0; i < STOP_BURST; i++) sendMulticast("GO");

        int lap = 1;
        Token t = new Token(lap, 0, 0);
        long lapStart = System.nanoTime();
        doTurn(t);          // eigener Zug zu Rundenbeginn
        forward(t);
        // Erste Runde enthält JVM-Warmup aller Knoten → großzügiges Budget; danach straffer Idle-Timeout.
        unicast.setSoTimeout(startupTimeoutMs);

        while (running) {
            DatagramPacket pkt = receiveUnicast();
            if (pkt == null) {            // Timeout: Runde nicht abgeschlossen → Stall
                if (running) printSummary("stalled");
                return;
            }
            Token rt = Token.parse(payload(pkt));
            if (rt == null) continue;     // unbekanntes Datagramm ignorieren

            // Rundenende: Token kehrte von Rang n-1 zurück.
            roundDurationsNanos.add(System.nanoTime() - lapStart);
            completedLaps = rt.lap;
            if (completedLaps == 1) unicast.setSoTimeout(idleTimeoutMs); // Warmup vorbei
            fwTotal = rt.fwTotal;
            logLine("lap-end lap=" + rt.lap + " fired=" + rt.fired + " fwTotal=" + rt.fwTotal);

            if (rt.fired == 0) consecutiveQuietLaps++; else consecutiveQuietLaps = 0;
            if (consecutiveQuietLaps >= k) {
                terminate();
                return;
            }

            // Nächste Runde: fired zurücksetzen, fwTotal kumulativ behalten.
            lap = rt.lap + 1;
            Token nt = new Token(lap, 0, rt.fwTotal);
            lapStart = System.nanoTime();
            doTurn(nt);
            forward(nt);
        }
    }

    private void terminate() {
        stopping = true;   // eigenes, per Loopback zurückkommendes STOP nicht auf sich selbst anwenden
        for (int i = 0; i < STOP_BURST; i++) {
            try {
                sendMulticast("STOP");
            } catch (IOException e) {
                logLine("STOP send failed: " + e.getMessage()); // SUMMARY trotzdem ausgeben
            }
            sleepQuietly(10);
        }
        printSummary("ok");
    }

    // =====================================================================================
    //  Follower (rank != 0)
    // =====================================================================================

    private void runFollower() {
        long startupDeadline = System.currentTimeMillis() + startupTimeoutMs;
        boolean tokenHandled = false; // Idle-Exit erst, nachdem uns das Token mind. einmal erreicht hat
        while (running) {
            DatagramPacket pkt = receiveUnicast();
            if (pkt == null) {                          // Timeout
                // Vor dem ersten Token geduldig sein: der erste Umlauf ist wegen JVM-Warmup langsam,
                // GO heißt noch nicht, dass das Token uns schon erreicht hat.
                if (tokenHandled) {                     // Ring lief für uns, jetzt still → Idle-Exit
                    logLine("idle-timeout exit");
                    return;
                }
                if (System.currentTimeMillis() > startupDeadline) {
                    logLine("startup-timeout exit");
                    return;
                }
                continue;                               // weiter auf erstes Token warten
            }
            Token t = Token.parse(payload(pkt));
            if (t == null) continue;
            ringActive = true;                          // erstes Token markiert aktiven Ring
            try {
                doTurn(t);
                forward(t);
                tokenHandled = true;
            } catch (IOException e) {
                if (running) logLine("forward failed: " + e.getMessage());
                return;
            }
        }
    }

    // =====================================================================================
    //  Token-Zug (gemeinsam)
    // =====================================================================================

    /** Zündet mit Wahrscheinlichkeit p, halbiert p und schreibt das Ergebnis ins Token. */
    private void doTurn(Token t) throws IOException {
        if (ThreadLocalRandom.current().nextDouble() < p) {
            sendMulticast("FIRE " + rank + " " + t.lap);
            fireworksSent++;
            t.fired = 1;        // OR-Akkumulation: einmal gesetzt, bleibt für die Runde gesetzt
            t.fwTotal++;        // kumulierte Raketen über den ganzen Lauf
            logLine("FIRE lap=" + t.lap + " p=" + p);
        }
        p = p / 2.0;            // Zündwahrscheinlichkeit pro Durchlauf reduzieren
    }

    private void forward(Token t) throws IOException {
        sendUnicast(basePort + ((rank + 1) % n), t.format());
    }

    private void sendUnicast(int port, String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        unicast.send(new DatagramPacket(data, data.length, loopback, port));
    }

    // =====================================================================================
    //  Multicast-Listener-Thread
    // =====================================================================================

    private void multicastListenLoop() {
        byte[] buf = new byte[512];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                mcast.receive(pkt);
            } catch (IOException e) {
                if (!running) break;     // Socket beim Shutdown geschlossen
                continue;
            }
            String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
            handleControl(msg);
        }
    }

    private void handleControl(String msg) {
        String[] f = msg.split(" ");
        switch (f[0]) {
            case "GO" -> {
                ringActive = true;
                goLatch.countDown();
            }
            case "FIRE" -> {
                fireworksObserved.incrementAndGet();
                if (verbose && f.length >= 3) logLine("RECV FIRE from=" + f[1] + " lap=" + f[2]);
            }
            case "STOP" -> { if (!stopping) requestShutdown(); }
            default -> { /* unbekannt: ignorieren */ }
        }
    }

    // =====================================================================================
    //  Hilfsfunktionen
    // =====================================================================================

    /** Empfängt ein Unicast-Datagramm; gibt null bei Timeout oder geschlossenem Socket zurück. */
    private DatagramPacket receiveUnicast() {
        byte[] buf = new byte[512];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        try {
            unicast.receive(pkt);
            return pkt;
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            return null;             // Socket geschlossen (Shutdown) oder Fehler
        }
    }

    private static String payload(DatagramPacket pkt) {
        return new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
    }

    private void sendMulticast(String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        mcast.send(new DatagramPacket(data, data.length, group, mcPort));
    }

    private void requestShutdown() {
        synchronized (shutdownLock) {
            if (shutdownDone || !running) return;
            running = false;
        }
        // Sockets schließen, um blockierende receive()-Aufrufe zu lösen.
        closeSockets();
    }

    private void shutdown() {
        synchronized (shutdownLock) {
            if (shutdownDone) return;
            shutdownDone = true;
            running = false;
        }
        closeSockets();
        // Unter demselben Monitor wie logLine() schließen → kein Race mit dem Listener-Daemon.
        synchronized (this) {
            if (logWriter != null) {
                try { logWriter.flush(); logWriter.close(); } catch (IOException ignored) {}
                logWriter = null;
            }
        }
    }

    private void closeSockets() {
        try { if (mcast != null && !mcast.isClosed()) mcast.close(); } catch (Exception ignored) {}
        if (unicast != null && !unicast.isClosed()) unicast.close();
    }

    private void printSummary(String status) {
        double min = 0, mean = 0, max = 0;
        if (!roundDurationsNanos.isEmpty()) {
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE, sum = 0;
            for (long d : roundDurationsNanos) {
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
                sum += d;
            }
            min  = lo / 1_000_000.0;
            max  = hi / 1_000_000.0;
            mean = (sum / (double) roundDurationsNanos.size()) / 1_000_000.0;
        }
        // Locale.US erzwingt Dezimalpunkt → CSV-Parsing im Python-Script bleibt stabil.
        System.out.printf(Locale.US,
                "SUMMARY n=%d status=%s rounds=%d multicasts=%d "
                + "rt_min_ms=%.3f rt_mean_ms=%.3f rt_max_ms=%.3f p0=%s k=%d%n",
                n, status, completedLaps, fwTotal, min, mean, max, p0, k);
        System.out.flush();
    }

    private synchronized void logLine(String s) {
        if (logWriter == null) return;
        try {
            logWriter.write(System.nanoTime() + " " + s);
            logWriter.newLine();
        } catch (IOException ignored) {}
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =====================================================================================
    //  Token: kleines, veränderliches Datagramm-Format "TOKEN <lap> <fired> <fwTotal>"
    // =====================================================================================
    static final class Token {
        int lap;
        int fired;     // 0/1: hat in dieser Runde jemand gezündet?
        long fwTotal;  // kumulierte Raketen über den ganzen Lauf

        Token(int lap, int fired, long fwTotal) {
            this.lap = lap;
            this.fired = fired;
            this.fwTotal = fwTotal;
        }

        String format() {
            return "TOKEN " + lap + " " + fired + " " + fwTotal;
        }

        static Token parse(String s) {
            String[] f = s.split(" ");
            if (f.length < 4 || !"TOKEN".equals(f[0])) return null;
            try {
                return new Token(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Long.parseLong(f[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
