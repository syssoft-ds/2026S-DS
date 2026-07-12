# Aufgabe 4 – Konsistenz

Die Implementierung liegt in `sim4da-S26/test/org/oxoo2a/portfolio/`:

| Datei | Inhalt |
|---|---|
| `FireworkRing.java` | Feuerwerk-Simulation (Basis aus Aufgabe 3) inkl. aller Konsistenzmechanismen |
| `FireworkConsistencyTest.java` | JUnit-Tests: Baseline, Erkennungs- und Vermeidungs-Demonstrationen, Skalierungsstatistik |

Ausführen (im Verzeichnis `sim4da-S26`):

```
./gradlew test --tests "org.oxoo2a.portfolio.FireworkConsistencyTest"
```

## Geht alles mit rechten Dingen zu?

In der UDP-Implementierung (Aufgabe 1/2) reisen Token (Unicast) und Raketen
(Multicast) über unabhängige Kanäle: Das Token kann einen Multicast real
überholen, Multicasts können verloren gehen. In der Simulation (Aufgabe 3)
ist die Zustellung zwar zuverlässig, aber sim4da erlaubt über
`SimulationBehavior` eine **nicht-FIFO-Mailbox-Auswahl** — damit treten
dieselben Umordnungs-Phänomene auf. Die Prozesse können also in beiden
Welten eine **inkonsistente Sicht auf den Programmablauf** bekommen, z.B.
eine Rakete „aus der Vergangenheit" beobachten oder terminieren, bevor sie
alle Raketen gesehen haben.

## Konsistenzkriterien

**C1 Token-Integrität.** Es kreist zu jedem Zeitpunkt genau ein Token; jeder
Prozess sieht die Rundennummern streng monoton um 1 wachsend. Verletzungen:
`TOKEN_DUPLICATE`, `TOKEN_LOST_ROUND`.

**C2 Vollständigkeit der Beobachtung.** Jeder Prozess beobachtet jede fremde
Rakete genau einmal — insbesondere darf kein Prozess terminieren, ohne alle
Raketen gesehen zu haben. Verletzungen: `FIREWORK_LOST`, `FIREWORK_DUPLICATE`.

**C3 Kausale Reihenfolge.** Der Broadcast einer Rakete passiert, *bevor* der
Zünder das Token weiterreicht. Die Rakete ist damit kausal vor jedem
Token-Empfang einer späteren Runde — und innerhalb derselben Runde vor dem
Token-Empfang aller im Ring nachfolgenden Prozesse. Beobachtet ein Prozess
eine Rakete erst nach dem kausal nachfolgenden Token, ist seine Sicht
inkonsistent. Verletzung: `FIREWORK_STALE`.

**C4 Terminierungskonsistenz.** Alle Prozesse beenden die Anwendung mit
derselben Sicht (Rundenzahl, Gesamtzahl gezündeter Raketen), und die
Entscheidung „k stille Runden" beruht auf einer konsistenten Informationsbasis.

## Mechanismen

### Erkennung (immer aktiv)

* Das Token trägt **Rundennummer** und einen **kumulativen Zähler**
  `totalFired`; jede Rakete trägt `(origin, round, originSeq)`; alle
  Nachrichten tragen **Lamport-Zeitstempel**, sodass Meldungen global kausal
  einzuordnen sind.
* C1: Rundennummer ≤ zuletzt verarbeitete Runde ⇒ Duplikat; Sprung > +1 ⇒
  verlorene Runde.
* C2: Bei Terminierung vergleicht jeder Prozess seine beobachteten Raketen
  mit dem Sollwert `totalFired − eigene Zündungen` aus der STOP-Nachricht;
  `(origin, originSeq)`-Paare entlarven Duplikate.
* C3: Rakete aus Runde < aktuelle Runde, oder aus der aktuellen Runde von
  einem im Ring vorausliegenden Prozess ⇒ kausal verspätet.
* Alle Verletzungen werden mit Knoten, Typ, Lamport-Zeit und Detailtext im
  `ConsistencyReport` gemeldet.

### Vermeidung (zuschaltbar, `Config.avoidance`)

* **Flush vor Token-/STOP-Verarbeitung:** Bevor ein Prozess ein Token der
  Runde r verarbeitet, liest er gezielt so lange Raketen aus der Mailbox, bis
  er den im Token mitgeführten Stand `totalFired − eigene` erreicht hat. Da
  die Zustellung in die Mailbox synchron mit dem Broadcast erfolgt, liegen
  alle kausal vorausgehenden Raketen garantiert schon dort — der Flush
  blockiert nie. Damit werden C2- und C3-Verletzungen *konstruktiv
  verhindert*, nicht nur erkannt.
* **Verwerfen veralteter Token:** Ein als Duplikat erkanntes Token wird beim
  ersten Empfänger gemeldet und verworfen, statt weiter durch den Ring zu
  wandern (C1). Der Koordinator startet aus einer veralteten Tokenrückkehr
  niemals eine neue Runde.
* **Deterministische Terminierung (by design):** Das `firedInRound`-Flag
  reist im Token mit; der Koordinator zählt stille Runden anhand des Tokens
  statt anhand asynchron eintreffender Multicasts (C4). Die STOP-Nachricht
  transportiert das verbindliche Endergebnis.

## Demonstration / Ergebnisse

Störquellen in den Tests:

1. **Nicht-FIFO-Zustellung** (zufällige Mailbox-Auswahl) plus zufällige
   Verarbeitungszeiten pro Prozess (`hopDelayMillis`) — modelliert
   unterschiedlich schnelle Knoten bzw. UDP-Umordnung.
2. **Fehlerinjektion:** Prozess n/2 dupliziert das Token in Runde 1.

| Szenario | Ergebnis |
|---|---|
| FIFO-Baseline (n=16) und Skalierung n=2…1024 | 0 Verletzungen, alle Sichten vollständig |
| nicht-FIFO, Erkennung (10 Läufe, n=8, p₀=0.95) | **70 × `FIREWORK_STALE`** gemeldet |
| nicht-FIFO, Vermeidung (gleiche Seeds) | **0** Verletzungen, jeder Prozess terminiert mit vollständiger Sicht |
| Token-Duplikat, Erkennung | 4 × `TOKEN_DUPLICATE` (jeder nachfolgende Prozess + Koordinator) |
| Token-Duplikat, Vermeidung | genau 1 Meldung — Duplikat stirbt beim ersten Empfänger |

Die Statistik zu Aufgabe 3 (analog zu Aufgabe 1/2) liegt in
`results_aufgabe3.csv` (n = 2 … 1024, Runden, Multicasts, min/Ø/max
Rundenzeit).

### Bemerkenswertes Framework-Detail

`RandomValues.getLong(0, queueSize−1)` schneidet `v·(queueSize−1)` ab: Mit
einem Supplier, der Werte aus `[0,1)` liefert (z.B. `Math::random`), ergibt
das bei Queue-Länge 2 **immer Index 0** — die „zufällige" Auswahl ist dann
faktisch FIFO und Umordnung kann nie auftreten. Die Tests verwenden deshalb
einen Supplier, der exakt 0.0 oder 1.0 liefert (älteste oder neueste
Nachricht). Ebenso entsteht Umordnung überhaupt erst, wenn sich ≥ 2
Nachrichten gleichzeitig in einer Mailbox befinden; bei gleichgetakteten
Prozessen konsumiert jeder Prozess jede Nachricht sofort — erst zufällige
Verarbeitungszeiten öffnen das Zeitfenster. Beides ist ein schönes Beispiel
dafür, wie leicht eine Simulation Nebenläufigkeitseffekte verstecken kann
(relevant für den Vergleich Aufgabe 1/2 vs. 3 im Bericht).
