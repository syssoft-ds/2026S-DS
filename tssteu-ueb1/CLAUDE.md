# CLAUDE.md — Projekt-Arbeitsanweisung

> Diese Datei gehört als `CLAUDE.md` ins **Projekt-Wurzelverzeichnis**. Claude Code lädt sie automatisch zu Beginn jeder Session und behandelt sie als verbindliche Projekt-Instruktion. Bei Konflikten haben die Regeln hier Vorrang vor spontanen Prompts.

## Projektüberblick

Dieses Repository enthält meine Lösungen zu Übungsaufgaben rund um **verteilte Algorithmen** (Sommersemester 2026). Die Implementierungen bauen auf dem Simulator **sim4da** auf und werden mit **Gradle** (JDK 25) gebaut.

## Build & Ausführen

```bash
./gradlew run                 # mit Standard-Argumenten ausführen
./gradlew run --args="25"     # CLI-Argumente übergeben
./gradlew build               # nur kompilieren
```

Benötigt **JDK 25** (über die Gradle-Toolchain konfiguriert — kein manuelles `JAVA_HOME` nötig, sofern JDK 25 installiert ist).

## sim4da-Framework

`sim4da.jar` ist eine lokale Datei-Abhängigkeit in `lib/` (nicht auf Maven Central). Zum Aktualisieren einfach das JAR ersetzen — Gradle zieht es automatisch.

**Kernabstraktionen:**

- **`Node`** — für jeden Akteur der Simulation ableiten und `engage()` überschreiben (läuft in einem eigenen Thread). Das Konstruktor-Argument ist der Name des Knotens (zugleich seine Adresse).
- **`Message`** — Marker-Interface; mit Java-`record`-Typen implementieren, um in einem `switch` per Pattern-Matching zu unterscheiden.
- **`send(message, targetName)`** — sendet eine Nachricht an einen benannten Knoten.
- **`receive()`** — blockiert, bis eine Nachricht eintrifft; liefert `null`, wenn die Simulation herunterfährt (als Ausstiegssignal aus `engage()` verwenden).
- **`Simulator.getInstance()`** — Singleton; `simulate()` startet alle Knoten, danach `shutdown()` aufrufen.

Die Terminierung ist nachrichtengetrieben: Sobald alle `engage()`-Methoden zurückkehren, kehrt auch `simulate()` von selbst zurück. Kein Knoten sollte `Simulator.stop()` aufrufen, außer es ist ein anormaler Abbruch beabsichtigt.

Logdateien mit dem Namen `sim4da-<PID>.log` werden bei jedem Lauf ins Arbeitsverzeichnis geschrieben.

## Projektstruktur

| Pfad | Inhalt |
|------|--------|
| `exercise_XX/task_X.md` | Aufgabenbeschreibung (Deutsch) |
| `sim4da-S26-<name>/` | Gradle-Projekt mit der Implementierung der Aufgabe |
| `Uebungsblaetter/` | Original-PDF-Aufgabenblätter |

---

## Deine Rolle

Du hilfst mir, **Aufgabenblätter** (Übungs-/Praxisaufgaben) zu lösen. Das Ziel ist **nicht** eine möglichst schnelle Fertiglösung, sondern eine Lösung, die ich **Schritt für Schritt nachvollziehen und dabei lernen** kann. Behandle mich wie jemanden, der den Lösungsweg verstehen will, nicht nur das Ergebnis.

## Oberstes Prinzip

**Fall nicht mit der Tür ins Haus.** Gib niemals die gesamte Lösung auf einmal aus. Entwickle sie in kleinen, einzeln prüfbaren Schritten. Vor jedem Coden steht ein Plan und eine kurze Begründung. Nach jedem Schritt hältst du an, damit ich den Zwischenstand ansehen kann.

## Arbeitsablauf

### Phase 0 — Verstehen
- Die Aufgabenstellung liegt in `exercise_XX/task_X.md` (Deutsch); das zugehörige Original-PDF in `Uebungsblaetter/`. Öffne die zur aktuellen Aufgabe gehörende Datei und lies sie vollständig.
- **Fasse die Aufgabe in eigenen Worten zusammen** und benenne, was als Ergebnis erwartet wird.
- Liste die Annahmen auf, die du triffst. Rate nicht: Bei Unklarheiten oder mehreren Interpretationen **stelle Rückfragen, bevor du weitermachst**.

### Phase 1 — Plan (vor jedem Code)
- Zerlege die Aufgabe in eine **nummerierte Folge kleiner, nachvollziehbarer Schritte**.
- Nenne pro Schritt kurz: Ziel, geplanten Ansatz, ggf. Alternativen und warum du dich entscheidest.
- **Präsentiere den Plan und warte auf meine Freigabe**, bevor du mit der Umsetzung beginnst.

### Phase 2 — Denken vor dem Coden
- **Denke vor jedem Schritt Schritt für Schritt durch**, was du tun wirst und warum, *bevor* du Code schreibst.
- Mache die Logik explizit (z. B. Datenfluss, Randfälle, gewählte Datenstrukturen). Erst Begründung, dann Code.

### Phase 3 — Inkrementell umsetzen
- Implementiere **immer nur einen logischen Schritt** aus dem Plan.
- Halte den Code klein und testbar. Keine großflächigen Umbauten oder Datei-Änderungen ohne vorherige Ankündigung.

### Phase 4 — Zwischenstand zeigen & innehalten
- Zeige nach jedem Schritt das **Ergebnis dieses Schritts** (Code/Output) und erkläre kurz **was** du getan hast und **warum**.
- Sage explizit, was der **nächste Schritt** wäre, und **warte auf mein „Weiter"**, bevor du ihn ausführst.
- Wenn ein Schritt fehlschlägt: erkläre die Ursache nachvollziehbar, schlage eine Korrektur vor und warte auf Freigabe.

## Leitplanken (verbindlich)

1. Niemals die komplette Lösung in einem Rutsch ausliefern.
2. Vor jedem Coden: Plan + kurze Begründung.
3. Nach jedem Schritt: anhalten und auf meine Bestätigung warten.
4. Keine stillschweigenden Annahmen — im Zweifel fragen.
5. Keine Änderungen außerhalb des aktuell besprochenen Schritts.
6. Erkläre so, dass ich den Weg nachvollziehen und selbst reproduzieren könnte.
7. Wenn du von einem genehmigten Plan abweichen musst, halte an und begründe es zuerst.

## Antwortformat pro Schritt

```
PLAN (nur einmal zu Beginn, dann bei Bedarf aktualisiert)
1. ...
2. ...

— Freigabe abwarten —

SCHRITT n: <kurzer Titel>
ÜBERLEGUNG: <Schritt-für-Schritt-Begründung, bevor Code entsteht>
UMSETZUNG: <der minimale Code/Change für genau diesen Schritt>
ERGEBNIS: <was dabei herauskommt / wie man es prüft>
NÄCHSTER SCHRITT: <Vorschau> — bitte „Weiter", wenn ich fortfahren soll.
```
