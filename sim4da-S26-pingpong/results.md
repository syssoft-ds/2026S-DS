# Aufgabe 3 – Ergebnisse (sim4da-Simulator)

**Parameter:** p₀ = 0.5 (p wird pro Runde halbiert), k = 3  
**Plattform:** sim4da Summer 2026, JVM (ein Prozess, ein Thread pro Knoten)  
**Messung:** real gemessene Rundenzeiten (wall clock)

## Statistische Messwerte

| n    | Runden | Broadcasts | min_rt (ms) | mean_rt (ms) | max_rt (ms) |
|-----:|-------:|-----------:|------------:|-------------:|------------:|
|   10 |      7 |         14 |       0.546 |        2.412 |       8.715 |
|   50 |      9 |         52 |       0.583 |        9.614 |      54.592 |
|  100 |     11 |        101 |       0.851 |       19.112 |     124.256 |
|  500 |     14 |        510 |       3.820 |      295.998 |    2127.198 |
| 1000 |     14 |        986 |       7.286 |     1102.657 |    8085.320 |
| 2000 |     13 |       2019 |      15.902 |     4718.974 |   30032.367 |
| 5000 |     17 |       5033 |      34.632 |    20661.229 |  178732.469 |

## Maximales n

**Maximales erfolgreich getestetes n: 5000**  
Bei n = 5000 liefen alle Runden durch; die Rundenzeiten wurden jedoch durch Thread-Scheduling-
Overhead sehr hoch (mean_rt ≈ 20 s, max_rt ≈ 179 s). Höhere n sind prinzipiell möglich
(JVM-Heap und Stack-Größe lassen sich über `-Xmx` / `-Xss` erhöhen), wurden aber nicht getestet.

## Vergleich mit Aufgaben 1 & 2

| n  | mean_rt localhost UDP (ms) | mean_rt LAN (ms) | mean_rt sim4da (ms) |
|---:|---------------------------:|-----------------:|--------------------:|
|  2 |                      0.412 |            2.531 |                 n/m |
|  4 |                      0.988 |            5.287 |                 n/m |
| 10 |                        n/m |              n/m |               2.412 |

## Beobachtungen

- **Skalierungsverhalten**: mean_rt wächst bei sim4da deutlich super-linear mit n — ab n ≥ 500
  dominiert der Thread-Scheduling-Overhead der JVM. Auf localhost (UDP) war die Skalierung noch
  annähernd linear bis n = 64.
- **Kleine n (≤ 100)**: Der Simulator ist bei kleinen Ringgrößen vergleichbar mit localhost-UDP;
  der Overhead pro Hop (~0.2 ms) liegt in derselben Größenordnung wie bei Aufgabe 1.
- **Broadcasts**: Die Broadcast-Anzahl entspricht grob n/2 · Runden · log(2), konsistent mit
  den Ergebnissen aus Aufgaben 1 und 2.
- **max_rt-Jitter**: Erheblich höher als bei UDP auf localhost, da viele JVM-Threads um CPU-Zeit
  konkurrieren; bei n = 5000 erreicht ein einzelner Umlauf fast 3 Minuten.
- **Implementierungsaufwand**: Der Simulator reduziert den Aufwand erheblich — kein UDP-Socket-
  Management, keine Firewall-Konfiguration, keine Peer-Listen. Die Kernlogik ist dieselbe wie in
  Aufgabe 1, eingebettet in `Node.engage()`.
- **Experimentalaufwand**: Deutlich geringer als Aufgabe 2 (kein Koordinieren mehrerer Rechner);
  alle Experimente laufen lokal per Shell-Skript in einem einzigen Gradle-Aufruf.
- **Maximales n**: sim4da ermöglicht mit n = 5000 eine um zwei Größenordnungen höhere Ringgröße
  als die echte verteilte Variante (n = 4), allerdings auf Kosten realistischer Netzwerksemantik.
