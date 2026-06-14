package org.oxoo2a.portfolio;

import org.junit.jupiter.api.Test;
import org.oxoo2a.portfolio.FireworkRing.Config;
import org.oxoo2a.portfolio.FireworkRing.NodeStats;
import org.oxoo2a.portfolio.FireworkRing.RunResult;
import org.oxoo2a.portfolio.FireworkRing.ViolationType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aufgabe 3: Baseline-Simulation und Skalierungsstatistik.
 * Aufgabe 4: Demonstration der Konsistenzpruefungen — erkannte Verletzungen
 * bei nicht-FIFO-Zustellung bzw. Token-Duplikat, Vermeidung per Flush/Drop.
 */
public class FireworkConsistencyTest {

    /**
     * Aufgabe 3 (Baseline): FIFO-Zustellung, keine Fehlerinjektion. Der Lauf
     * muss frei von Konsistenzverletzungen sein und jeder Knoten muss alle
     * fremden Raketen beobachtet haben.
     */
    @Test
    void a3_baselineIstKonsistent() {
        RunResult r = FireworkRing.run(Config.of(16, 0.5, 3));
        System.out.println(r.summaryLine());

        assertTrue(r.violations().isEmpty(), "Baseline darf keine Verletzungen melden: " + r.violations());
        assertTrue(r.rounds() >= 3, "Mindestens k stille Runden noetig");
        for (NodeStats s : r.nodeStats()) {
            assertEquals(s.expectedObserved(), s.observed(),
                    "Knoten " + s.id() + " hat nicht alle fremden Raketen beobachtet");
        }
    }

    /**
     * Aufgabe 4 (Erkennung): Zufaellige Mailbox-Auswahl (nicht-FIFO) erzeugt
     * Situationen, in denen ein Token eine Rakete "ueberholt". Die
     * Konsistenzpruefungen muessen das als FIREWORK_STALE bzw. FIREWORK_LOST
     * erkennen und melden.
     */
    @Test
    void a4_nichtFifoZustellungWirdErkannt() {
        long stale = 0, lost = 0;
        for (long seed = 1; seed <= 10; seed++) {
            RunResult r = FireworkRing.run(
                    new Config(8, 0.95, 3, true, false, 2, 0, seed));
            stale += r.violationCount(ViolationType.FIREWORK_STALE);
            lost += r.violationCount(ViolationType.FIREWORK_LOST);
        }
        System.out.printf("Nicht-FIFO ueber 10 Laeufe: %d FIREWORK_STALE, %d FIREWORK_LOST%n", stale, lost);
        assertTrue(stale + lost > 0,
                "Bei zufaelliger Zustellung muessen Inkonsistenzen erkannt werden");
    }

    /**
     * Aufgabe 4 (Vermeidung): Gleiche nicht-FIFO-Stoerung, aber mit
     * aktivierter Vermeidung (Flush kausal vorausgehender Raketen vor jeder
     * Token-/Stop-Verarbeitung). Es duerfen keine kausalen Verletzungen und
     * keine verlorenen Raketen mehr auftreten, und jeder Knoten terminiert
     * mit vollstaendiger Sicht.
     */
    @Test
    void a4_vermeidungVerhindertInkonsistenzen() {
        for (long seed = 1; seed <= 10; seed++) {
            RunResult r = FireworkRing.run(
                    new Config(8, 0.95, 3, true, true, 2, 0, seed));
            assertEquals(0, r.violationCount(ViolationType.FIREWORK_STALE),
                    "Vermeidung muss kausale Verletzungen verhindern (seed " + seed + ")");
            assertEquals(0, r.violationCount(ViolationType.FIREWORK_LOST),
                    "Vermeidung muss verlorene Raketen verhindern (seed " + seed + ")");
            for (NodeStats s : r.nodeStats()) {
                assertEquals(s.expectedObserved(), s.observed(),
                        "Knoten " + s.id() + " terminierte ohne vollstaendige Sicht (seed " + seed + ")");
            }
        }
    }

    /**
     * Aufgabe 4 (C1, Erkennung): Fehlerinjektion — Knoten n/2 dupliziert das
     * Token in Runde 1. Jeder nachfolgende Knoten muss das Duplikat anhand
     * der Rundennummer erkennen und melden; der Koordinator startet daraus
     * keine Phantom-Runde.
     */
    @Test
    void a4_tokenDuplikatWirdErkannt() {
        RunResult r = FireworkRing.run(
                new Config(8, 0.3, 3, false, false, 0, 1, 7L));
        System.out.printf("Token-Duplikat: %d TOKEN_DUPLICATE gemeldet%n",
                r.violationCount(ViolationType.TOKEN_DUPLICATE));
        assertTrue(r.violationCount(ViolationType.TOKEN_DUPLICATE) > 0,
                "Das duplizierte Token muss erkannt werden");
    }

    /**
     * Aufgabe 4 (C1, Vermeidung): Gleiche Fehlerinjektion, aber mit
     * Vermeidung — das Duplikat wird beim ersten Knoten erkannt UND
     * verworfen, statt weiter durch den Ring zu wandern. Es bleibt bei
     * genau einer Meldung und die Beobachtung bleibt vollstaendig.
     */
    @Test
    void a4_tokenDuplikatWirdVerworfen() {
        RunResult r = FireworkRing.run(
                new Config(8, 0.3, 3, false, true, 0, 1, 7L));
        assertEquals(1, r.violationCount(ViolationType.TOKEN_DUPLICATE),
                "Das Duplikat muss genau einmal (beim ersten Empfaenger) gemeldet und dort verworfen werden");
        for (NodeStats s : r.nodeStats()) {
            assertEquals(s.expectedObserved(), s.observed(),
                    "Knoten " + s.id() + " terminierte ohne vollstaendige Sicht");
        }
    }

    /**
     * Aufgabe 3 (Statistik): Skalierungslaeufe mit wachsendem n, analog zu
     * den Experimenten aus Aufgabe 1/2. Schreibt results_aufgabe3.csv ins
     * Projektwurzelverzeichnis des Portfolios.
     */
    @Test
    void a3_skalierungsStatistik() throws IOException {
        List<String> csv = new ArrayList<>();
        csv.add(RunResult.CSV_HEADER);
        for (int n = 2; n <= 2048; n *= 2) {
            RunResult r = FireworkRing.run(Config.of(n, 0.5, 3));
            System.out.println(r.summaryLine());
            csv.add(r.csvLine());
            assertTrue(r.violations().isEmpty(), "n=" + n + " meldete Verletzungen");
        }
        Path out = Path.of("..", "results_aufgabe3.csv");
        Files.write(out, csv);
        System.out.println("CSV geschrieben: " + out.toAbsolutePath().normalize());
    }
}
