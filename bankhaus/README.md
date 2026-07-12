# Bankhaus — Schnappschuss eines konsistenten globalen Zustands

Lösung zu Übungsblatt 2 (Verteilte Systeme, Sommer 2026): ein verteiltes Kontensystem in **sim4da**
und ein Schnappschuss-Algorithmus nach dem **Koordinator-/Einfärbeverfahren** (Variante (a)).

## Dokumentation

| | |
|---|---|
| [AUFGABE_1.md](AUFGABE_1.md) | Die verteilte Anwendung: Konten, Überweisungen, Latenz, Nicht-FIFO |
| [AUFGABE_2.md](AUFGABE_2.md) | Der Schnappschuss-Algorithmus: Einfärbung, die vier Fälle, Annahmen, Terminierung |
| [AUFGABE_3.md](AUFGABE_3.md) | Konsistenznachweis, naiver Schnappschuss, Messreihen, Sequenzdiagramme |

## Ausführen

```bash
./gradlew run                 # Aufgabe 1 + 2: ein Lauf mit n = 5, vollständige Zustandsausgabe
./gradlew run --args="8"      # mit n = 8
./gradlew experiments         # Aufgabe 3: Messreihen -> results/*.csv  (ca. 4 min)
```

Ohne Gradle (JDK 25):

```bash
javac -d out -cp lib/sim4da.jar src/bankhaus/*.java
java -cp "out;lib/sim4da.jar" bankhaus.BankhausSimulation 5     # Windows; sonst ":" statt ";"
java -cp "out;lib/sim4da.jar" bankhaus.ConsistencyExperiments
```

## Quellen

| Datei | Rolle |
|---|---|
| `Messages.java` | Alle Nachrichten als `record` (Basis-, Kontroll- und Steuernachrichten) |
| `AccountNode.java` | Kontoprozess `P_i`: Überweisungen + Empfängerseite des Einfärbeverfahrens |
| `SnapshotCoordinator.java` | Koordinator: Multicast, Einsammeln, Abbruchbedingung, Ausgabe des globalen Zustands |
| `DelayedLinks.java` | Übertragungsverzögerung der Kanäle |
| `BankhausRun.java` | Aufbau eines Laufs (Knoten, Nicht-FIFO-Zustellung, Wachhund) |
| `Config.java`, `Results.java` | Parameter bzw. Messwerte |
| `BankhausSimulation.java` | `main` für Aufgabe 1 + 2 |
| `ConsistencyExperiments.java` | `main` für Aufgabe 3 |

## Ergebnis in einem Satz

192 während des laufenden Betriebs aufgenommene Schnappschüsse erfüllen ausnahmslos
`Σ Kontostände + Σ Kanalinhalte = S`, während der naive Schnappschuss an derselben Stelle in 64 von
120 Fällen Geld verliert oder erfindet.
