package bankhaus;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modelliert die Uebertragungszeit der Kanaele.
 *
 * <p>sim4da stellt eine Nachricht sofort in die Mailbox des Empfaengers; das
 * Zeitfenster, in dem eine Ueberweisung "unterwegs" ist, waere damit praktisch
 * leer. Statt {@code send} direkt aufzurufen, uebergeben die Knoten den
 * eigentlichen Sendevorgang hier als {@link Runnable} und lassen ihn erst nach
 * einer zufaellig bemessenen Verzoegerung ausfuehren. Zwischen dem Sendeereignis
 * im Prozess (Konto wird sofort belastet) und dem Zustellereignis vergeht dadurch
 * echte Zeit — genau das Fenster, in dem der Kanalzustand nicht leer ist.
 *
 * <p>Nebeneffekt: weil jede Nachricht ihre eigene Verzoegerung zieht, koennen sich
 * Nachrichten desselben Kanals ueberholen. Zusammen mit der abgeschalteten
 * FIFO-Zustellung der Mailbox (siehe {@link BankhausRun}) sind die Kanaele damit
 * konsequent nicht-FIFO.
 *
 * <p>Der Scheduler laeuft auf eigenen Daemon-Threads (nicht auf den Knoten-Threads
 * des Simulators). {@code close()} verwirft alle noch anstehenden Zustellungen und
 * verhindert so, dass ein Lauf in den naechsten hineinsendet.
 */
public final class DelayedLinks implements AutoCloseable {

    private final ScheduledExecutorService exec;

    public DelayedLinks(int threads) {
        this.exec = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "sim4da-link");
            t.setDaemon(true);
            return t;
        });
    }

    /** Fuehrt {@code delivery} nach {@code delayMs} Millisekunden aus. */
    public void after(int delayMs, Runnable delivery) {
        try {
            exec.schedule(delivery, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException alreadyClosed) {
            // Der Lauf ist beendet; die Zustellung interessiert niemanden mehr.
        }
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
