# udp-fireworks-simulated (Aufgabe 3)

Feuerwerk-Token-Ring aus Aufgabe 1, nachgebildet im In-Process-Simulator **sim4da**
(statt echter UDP-Sockets). Details, Ergebnisse und der Vergleich zu Aufgabe 1/2 stehen in
[`AUFGABE3.md`](AUFGABE3.md).

## Schnellstart

> Braucht **JDK 25** (sim4da.jar ist Java-25-Bytecode). Temurin 25 ist installiert
> (`winget install EclipseAdoptium.Temurin.25.JDK`); Gradle erkennt es automatisch.

```powershell
cd "Aufgabe 3\udp-fireworks-simulated"
.\gradlew.bat run --args="4 0.5 3"        # einmal: n=4, p0=0.5, k=3
python scripts\run_sim.py                 # Experiment-Reihe -> results/results.csv
```

`RingSimulation <n> <p0> <k>` gibt eine `SUMMARY`-Zeile aus
(`rounds`, `multicasts`, `rt_min/mean/max_ms`, `mode=sim`).

## Aufbau
- `src/firework/RingSimulation.java` — Ring-Knoten (`RingNode extends Node`) + Nachrichten-records
  (`Token`, `Fire`, `Stop`) + `main`. Knoten 0 ist Initiator: injiziert das Token, misst
  Rundenzeiten, terminiert nach `k` stillen Runden per `broadcast(Stop)`.
- `scripts/run_sim.py` — fährt die n-Rampe und schreibt `results.csv` (+ Plots).
