# udp-fireworks-consistency (Aufgabe 4)

Aufgabe-3-Simulation + **Konsistenz**: Lamport-Uhren, zweiphasige Terminierung mit
`Finalize`-Sammelrunde, Agreement-Check und Fehlerinjektion. Details & Ergebnisse in
[`AUFGABE4.md`](AUFGABE4.md).

> Braucht **JDK 25** (Temurin 25 installiert; Gradle erkennt es automatisch).

```powershell
cd "Aufgabe 4\udp-fireworks-consistency"
.\gradlew.bat run --args="8 0.5 3"          # verdict=ok (zuverlaessig)
.\gradlew.bat run --args="64 0.5 3 0.2"     # verdict=INCONSISTENT (Verlust injiziert)
python scripts\run_sim.py                   # Sweep -> results/results.csv
python scripts\run_sim.py --inject-loss 0.2 # -> results/results_loss0.2.csv
```

`RingSimulation <n> <p0> <k> [lossP]` gibt eine `CONSISTENCY`- und eine `SUMMARY`-Zeile aus.

## Konsistenzkriterien
- **K1** Token-Konsistenz (ein Token, monotone Laps), **K2** Agreement über die Feuerwerke
  (jeder Knoten sieht `fwTotal`), **K3** kausale Terminierung (`Stop` überholt kein Feuerwerk).
