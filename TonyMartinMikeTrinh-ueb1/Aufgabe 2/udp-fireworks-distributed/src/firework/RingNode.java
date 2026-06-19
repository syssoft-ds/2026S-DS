package firework;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ein Knoten im logischen Ring (Aufgabe 2 – "Feuerwerk an UDP-Nachrichten", verteilt).
 *
 * <p>n solcher Prozesse bilden einen Ring – in Aufgabe 2 <b>je einer pro realem Rechner</b>.
 * Ein Token ("Streichholz") kreist per <b>Unicast-UDP</b> von Knoten i zu Knoten (i+1) mod n.
 * Wer das Token hält, zündet mit Wahrscheinlichkeit p eine Rakete (Broadcast an alle) und
 * halbiert dann sein p. Knoten 0 ist Initiator: er injiziert das Token, misst Rundenzeiten,
 * zählt Runden und terminiert, wenn in k aufeinanderfolgenden Runden niemand gezündet hat.
 *
 * <p><b>Verteilung (Unterschied zu Aufgabe 1):</b> Die Adressierung kommt aus einer expliziten
 * <b>Membership-Tabelle</b> (rank → host:port) statt aus "127.0.0.1:basePort+rank". Quelle ist die
 * Umgebungsvariable {@code RING_MEMBERS} (Dateipfad mit Zeilen {@code rank host port} oder inline
 * {@code r=host:port;...}). Ohne {@code RING_MEMBERS} gilt exakt das lokale Aufgabe-1-Verhalten
 * (rank r → 127.0.0.1:basePort+r) – nützlich zum Testen zweier JVMs auf einem Rechner.
 *
 * <p>Broadcasts werden – der Aufgabe folgend – möglichst auf <b>UDP-Multicast</b> abgebildet,
 * sonst auf <b>n Unicast-Nachrichten</b> ({@code RING_BCAST=unicast}, Default im verteilten Modus,
 * da Android/Termux Multicast-Empfang i. d. R. mangels {@code WifiManager.MulticastLock} verwirft).
 *
 * <p>Weitere Umgebungsvariablen: {@code RING_BCAST=unicast|multicast}, {@code RING_BIND=<addr>}
 * (Bind-Adresse, Default verteilt {@code 0.0.0.0}, sonst {@code 127.0.0.1}),
 * {@code RING_MCAST_IF=<name|ip>} (Multicast-Interface, nur bei {@code RING_BCAST=multicast}).
 *
 * <p>Literatur: Ring-Koordination Coulouris §15.2; IP-Multicast / Gruppenkommunikation inkl.
 * Unicast-Emulation Coulouris §4.4.1 & §15.4 / Van Steen §4.4; UDP-Fehlermodell (Verlust)
 * Coulouris §4.2.1 / Van Steen §4.2; reale Netz-Latenz vs. lokal Coulouris §2.4.
 *
 * <p>Robustheit (Hybrid): Token wird bei Verlust NICHT neu gesendet (pure UDP-Semantik).
 * Stattdessen beendet sich jeder Knoten bei Leerlauf-Timeout selbst (kein Zombie).
 *
 * <p>Threads pro Knoten: (1) Token-/Hauptthread blockiert auf dem Unicast-Socket;
 * (2) Control-Listener behandelt READY/GO/FIRE/STOP. Geteilter Zustand ist unten markiert.
 */
public class RingNode {

    // --- Startup/Robustheit-Konstanten ---------------------------------------------------
    private static final int READY_INTERVAL_MS  = 150;    // Resend-Intervall der READY-Barriere
    private static final int STOP_BURST         = 5;      // wie oft STOP/GO gesendet wird

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

    // --- Verteilung (Aufgabe 2) -----------------------------------------------------------
    /** Adresstabelle rank → host:port. Lokal (Aufgabe 1) = 127.0.0.1:basePort+rank. */
    private final Member[] members;
    /** true, sobald RING_MEMBERS gesetzt war (echte Verteilung). */
    private final boolean distributed;
    /** "multicast" oder "unicast" (n-Unicast-Fallback der Broadcasts). */
    private final String broadcastMode;
    /** Bind-Adresse des Unicast-Sockets (0.0.0.0 verteilt, sonst 127.0.0.1). */
    private final InetAddress bindAddr;
    /** Gewünschtes Multicast-Interface (Name/IP) oder null. */
    private final String mcastIfPref;

    // --- Netzwerk -------------------------------------------------------------------------
    private final InetAddress loopback;          // 127.0.0.1
    private final InetAddress group;             // Multicast-Gruppe
    private DatagramSocket unicast;              // empfängt Token vom Vorgänger
    private MulticastSocket mcast;               // empfängt/sendet Broadcasts (Multicast bzw. Unicast)
    private NetworkInterface mcastIf;            // gewähltes Multicast-Interface (nur Multicast-Modus)

    // --- Geteilter Zustand zwischen Token-Thread und Control-Listener ---------------------
    /** Shutdown-Flag; von beiden Threads gelesen/geschrieben → volatile. */
    private volatile boolean running = true;
    /** true, sobald der Ring aktiv ist (GO gesehen oder erstes Token). Steuert den Idle-Exit. */
    private volatile boolean ringActive = false;
    /** Nur Knoten 0: true während des eigenen STOP-Bursts → eigenes (gelooptes) STOP ignorieren. */
    private volatile boolean stopping = false;
    /** Vom Control-Listener inkrementiert, beim Shutdown gelesen → Atomic. */
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

        // --- Membership-Tabelle: verteilt (RING_MEMBERS) oder lokal (Aufgabe-1-Verhalten) ---
        String membersEnv = System.getenv("RING_MEMBERS");
        if (membersEnv != null && !membersEnv.isBlank()) {
            this.members = parseMembers(membersEnv);
            this.distributed = true;
            if (members.length != n) {
                throw new IllegalArgumentException("RING_MEMBERS hat " + members.length
                        + " Eintraege, aber n=" + n + " (muessen uebereinstimmen)");
            }
        } else {
            this.distributed = false;
            Member[] m = new Member[n];          // rank r → 127.0.0.1:basePort+r
            for (int r = 0; r < n; r++) m[r] = new Member(loopback, basePort + r);
            this.members = m;
        }

        String bcastEnv = System.getenv("RING_BCAST");
        this.broadcastMode = (bcastEnv != null && !bcastEnv.isBlank())
                ? bcastEnv.trim().toLowerCase(Locale.ROOT)
                : (distributed ? "unicast" : "multicast");

        String bindEnv = System.getenv("RING_BIND");
        if (bindEnv != null && !bindEnv.isBlank()) {
            this.bindAddr = InetAddress.getByName(bindEnv.trim());
        } else if (distributed) {
            this.bindAddr = InetAddress.getByName("0.0.0.0"); // Wildcard: auf allen Interfaces erreichbar
        } else {
            this.bindAddr = loopback;
        }

        String ifEnv = System.getenv("RING_MCAST_IF");
        this.mcastIfPref = (ifEnv != null && !ifEnv.isBlank()) ? ifEnv.trim() : null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("Usage: RingNode <rank> <n> <basePort> <mcAddr> <mcPort> "
                    + "<p0> <k> [idleTimeoutMs] [ttl] [verbose] [startupTimeoutMs]");
            System.err.println("Verteilung via Umgebungsvariablen: RING_MEMBERS=<datei|inline> "
                    + "RING_BCAST=unicast|multicast RING_BIND=<addr> RING_MCAST_IF=<name|ip>");
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
                    + " distributed=" + distributed + " bcast=" + broadcastMode
                    + " bind=" + bindAddr.getHostAddress()
                    + " mcastIf=" + (mcastIf != null ? mcastIf.getName() : "n/a") + " ttl=" + ttl);
            for (int r = 0; r < members.length; r++) {
                logLine("member " + r + " = " + members[r].host.getHostAddress() + ":" + members[r].port);
            }
        }

        Thread listener = new Thread(this::controlListenLoop, "control-listener-" + rank);
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

    // Großer Empfangspuffer: bei vielen Knoten kommen READY/FIRE-Nachrichten in Bursts; ein zu
    // kleiner Kernel-Puffer würde sie verwerfen und so die Startbarriere künstlich begrenzen.
    private static final int RCV_BUFFER = 1 << 20; // 1 MiB (OS deckelt ggf.)

    private void setupSockets() throws IOException {
        // Unicast-Empfänger an bindAddr:members[rank].port (mit Retry gegen transiente Belegung).
        unicast = bindUnicastWithRetry(members[rank].port);
        unicast.setReceiveBufferSize(RCV_BUFFER);

        boolean useMulticast = broadcastMode.equals("multicast");

        // Control-/Broadcast-Socket. Multicast-Modus: alle binden mcPort + treten der Gruppe bei.
        // Unicast-Modus: jeder bindet mcPort+rank, damit auf einem geteilten Host (lokaler Test)
        // die n Unicast-Datagramme eindeutig zugestellt werden; über echte Rechner ist der Port
        // ohnehin pro Host frei.
        int controlPort = useMulticast ? mcPort : mcPort + rank;
        mcast = new MulticastSocket(null);
        mcast.setReuseAddress(true);
        mcast.setReceiveBufferSize(RCV_BUFFER);
        mcast.bind(new InetSocketAddress(controlPort));
        mcast.setTimeToLive(ttl);

        if (useMulticast) {
            mcast.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true); // eigene Pakete empfangen
            mcastIf = chooseMulticastInterface(distributed, mcastIfPref);
            InetSocketAddress groupSockAddr = new InetSocketAddress(group, mcPort);
            if (mcastIf != null) {
                mcast.setNetworkInterface(mcastIf);
                mcast.joinGroup(groupSockAddr, mcastIf);
            } else {
                mcast.joinGroup(group); // letzter Ausweg: Default-Interface (ältere API)
            }
        }
        // Unicast-Modus: kein joinGroup; der an mcPort+rank gebundene Socket empfängt die
        // Unicast-FIRE/GO/STOP-Datagramme direkt. Umgeht Androids fehlenden Multicast-Lock.
    }

    private DatagramSocket bindUnicastWithRetry(int port) throws IOException {
        java.net.BindException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return new DatagramSocket(new InetSocketAddress(bindAddr, port));
            } catch (java.net.BindException e) {
                last = e;
                sleepQuietly(200);
            }
        }
        throw last;
    }

    /**
     * Wählt ein multicast-fähiges IPv4-Interface. {@code pref} (Name/IP) hat Vorrang. Sonst:
     * im verteilten Modus das erste reale Interface (Multicast muss das LAN erreichen), lokal das
     * Loopback-Interface (host-lokal). Alle Knoten wählen deterministisch, damit sich Sender und
     * Empfänger finden.
     */
    private static NetworkInterface chooseMulticastInterface(boolean preferReal, String pref)
            throws IOException {
        NetworkInterface loop = null, real = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || !ni.supportsMulticast()) continue;
            boolean hasV4 = ni.getInterfaceAddresses().stream()
                    .anyMatch(ia -> ia.getAddress() instanceof Inet4Address);
            if (!hasV4) continue;
            if (pref != null && matchesInterface(ni, pref)) return ni; // explizit gewünscht
            if (ni.isLoopback()) { if (loop == null) loop = ni; }
            else if (real == null) { real = ni; }
        }
        if (preferReal) return (real != null) ? real : loop;
        return (loop != null) ? loop : real;
    }

    private static boolean matchesInterface(NetworkInterface ni, String pref) {
        if (pref.equalsIgnoreCase(ni.getName())) return true;
        String disp = ni.getDisplayName();
        if (disp != null && pref.equalsIgnoreCase(disp)) return true;
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            if (ia.getAddress() != null && pref.equals(ia.getAddress().getHostAddress())) return true;
        }
        return false;
    }

    // =====================================================================================
    //  Startbarriere
    // =====================================================================================

    /** Nicht-Initiator: meldet periodisch READY (Unicast an Knoten 0), bis der Ring aktiv ist. */
    private void startReadyAnnouncer() {
        Thread t = new Thread(() -> {
            try {
                while (running && !ringActive) {
                    sendUnicast(members[0], "READY " + rank); // direkt an Knoten 0
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
        // Multicast-READY). Das Token-Loop läuft noch nicht, der Unicast-Socket ist also frei
        // zum Sammeln. Zugriff nur durch diesen Thread.
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
        for (int i = 0; i < STOP_BURST; i++) broadcast("GO");

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
        stopping = true;   // eigenes, zurückkommendes STOP nicht auf sich selbst anwenden
        for (int i = 0; i < STOP_BURST; i++) {
            try {
                broadcast("STOP");
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
            broadcast("FIRE " + rank + " " + t.lap);
            fireworksSent++;
            t.fired = 1;        // OR-Akkumulation: einmal gesetzt, bleibt für die Runde gesetzt
            t.fwTotal++;        // kumulierte Raketen über den ganzen Lauf
            logLine("FIRE lap=" + t.lap + " p=" + p);
        }
        p = p / 2.0;            // Zündwahrscheinlichkeit pro Durchlauf reduzieren
    }

    private void forward(Token t) throws IOException {
        sendUnicast(members[(rank + 1) % n], t.format());
    }

    private void sendUnicast(Member m, String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        unicast.send(new DatagramPacket(data, data.length, m.host, m.port));
    }

    // =====================================================================================
    //  Control-/Broadcast-Listener-Thread
    // =====================================================================================

    private void controlListenLoop() {
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

    /**
     * Broadcast an alle Knoten. Multicast-Modus: ein Datagramm an die Gruppe. Unicast-Modus
     * (Fallback gem. Aufgabe): je ein Unicast an jeden Knoten – inkl. sich selbst, damit der Sender
     * sein eigenes FIRE wie beim Multicast-Loopback mitzählt. Ziel-Control-Port = mcPort+rank.
     */
    private void broadcast(String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        if (broadcastMode.equals("multicast")) {
            mcast.send(new DatagramPacket(data, data.length, group, mcPort));
        } else {
            for (int i = 0; i < members.length; i++) {
                mcast.send(new DatagramPacket(data, data.length, members[i].host, mcPort + i));
            }
        }
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
        // bcast=<mode> macht eindeutig, ob ein Feuerwerk 1 Multicast oder n Unicasts war.
        System.out.printf(Locale.US,
                "SUMMARY n=%d status=%s rounds=%d multicasts=%d "
                + "rt_min_ms=%.3f rt_mean_ms=%.3f rt_max_ms=%.3f p0=%s k=%d bcast=%s%n",
                n, status, completedLaps, fwTotal, min, mean, max, p0, k, broadcastMode);
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
    //  Membership-Parsing: Datei (Zeilen "rank host port") oder inline "r=host:port;..."
    // =====================================================================================
    private static Member[] parseMembers(String spec) throws IOException {
        List<String> lines = new ArrayList<>();
        // '=' kennzeichnet die Inline-Form (r=host:port;...). Ein Dateipfad enthaelt kein '=' und
        // wuerde sonst auf Windows an Path.of(...) scheitern (':' ist im Pfad illegal).
        if (spec.contains("=")) {
            for (String part : spec.split(";")) {        // inline: r=host:port;r2=host2:port2
                String s = part.trim();
                if (s.isEmpty()) continue;
                int eq = s.indexOf('=');
                int colon = s.lastIndexOf(':');
                if (eq < 0 || colon < eq) {
                    throw new IllegalArgumentException("RING_MEMBERS inline ungueltig: '" + part + "'");
                }
                lines.add(s.substring(0, eq).trim() + " "
                        + s.substring(eq + 1, colon).trim() + " "
                        + s.substring(colon + 1).trim());
            }
        } else {
            Path p = Path.of(spec);
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("RING_MEMBERS Datei nicht gefunden: " + spec);
            }
            lines.addAll(Files.readAllLines(p));
        }

        Map<Integer, Member> map = new HashMap<>();
        int maxRank = -1;
        for (String ln : lines) {
            String s = ln.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] f = s.split("\\s+");
            if (f.length < 3) {
                throw new IllegalArgumentException("RING_MEMBERS Zeile ungueltig: '" + ln + "'");
            }
            int r = Integer.parseInt(f[0]);
            InetAddress host = InetAddress.getByName(f[1]);
            int port = Integer.parseInt(f[2]);
            map.put(r, new Member(host, port));
            maxRank = Math.max(maxRank, r);
        }
        if (maxRank < 0) throw new IllegalArgumentException("RING_MEMBERS leer/unlesbar: " + spec);
        Member[] arr = new Member[maxRank + 1];
        for (int r = 0; r <= maxRank; r++) {
            arr[r] = map.get(r);
            if (arr[r] == null) {
                throw new IllegalArgumentException("RING_MEMBERS unvollstaendig: rank " + r + " fehlt");
            }
        }
        return arr;
    }

    /** Ein Ring-Teilnehmer: feste Adresse (host:port) für den Token-Unicast. */
    static final class Member {
        final InetAddress host;
        final int port;

        Member(InetAddress host, int port) {
            this.host = host;
            this.port = port;
        }
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
