# Bankhaus

Lösung zu **Übungsblatt 2, Verteilte Systeme, Sommer 2026** auf Basis von
[sim4da-S26](https://github.com/syssoft-ds/sim4da-S26): ein verteiltes
Kontensystem mit **Koordinator-/Einfärbeverfahren** (Variante a) — robust
unter abgeschalteter FIFO-Zustellung — sowie einem naiven
Vergleichs-Schnappschuss, der Inkonsistenzen demonstriert.

## Ausführen
JDK 25 wird benötigt (sim4da.jar ist dafür gebaut); der Gradle-Wrapper lädt
bei Bedarf automatisch ein passendes JDK herunter.

```bash
./gradlew run                 # Demo: n = 6, abwechselnd eingefärbt/naiv, 30 s
./gradlew run --args="10 20"  # n = 10, 20 s
./gradlew test                # Konsistenznachweise + Statistik (schreibt stats.csv)
```

Erwartete Ausgabe pro Einfärbe-Runde: alle Kontostände, alle Kanalinhalte
(`Pi->Pj : [Beträge]`), Defizit, Kontrollnachrichten und `KONSISTENT`.
Naive Schnappschüsse melden regelmäßig `INKONSISTENT`.

Die Visualisierung (`visuals/index.html`) läuft ohne Build direkt im Browser.
