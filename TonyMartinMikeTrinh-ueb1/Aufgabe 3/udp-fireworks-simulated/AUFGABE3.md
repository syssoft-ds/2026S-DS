# Aufgabe 3 – Ein simuliertes Feuerwerk (sim4da)

Dieselbe Anwendung wie Aufgabe 1 (logischer Ring, ein Token kreist; wer es hält, zündet mit
Wahrscheinlichkeit `p` eine Rakete als **Broadcast** und halbiert `p`; Terminierung nach `k`
stillen Runden) – jetzt **nicht** mehr mit echten UDP-Sockets, sondern im **In-Process-Simulator
[sim4da](https://github.com/syssoft-ds/sim4da-S26)**. Ausgangspunkt ist dessen Test
`OneRingToRuleThemAll` (record-Nachrichten, `RingSegment extends Node`, Nachfolger `(i+1)%n`,
`simulate()/shutdown()`).

> Literatur: Ring-/Token-Koordination *Coulouris §15.2*; Gruppen-/Broadcast-Kommunikation
> *Coulouris §4.4.1 & §15.4 / Van Steen §4.4*; UDP-Fehlermodell vs. zuverlässige In-Process-Zustellung
> *Coulouris §4.2.1 / Van Steen §4.2*; reale vs. simulierte Zeit/Latenz *Coulouris §2.4*.

---

## Was sich gegenüber Aufgabe 1/2 ändert

| Aspekt | Aufgabe 1/2 (UDP) | Aufgabe 3 (sim4da) |
|---|---|---|
| Knoten = | Prozess (+ Listener-Thread) | **Thread** in **einer** JVM |
| Token-Kanal | Unicast-UDP (best-effort, Verlust möglich) | `send(msg, name)` über **Mailbox** (zuverlässig, FIFO) |
| Broadcast | UDP-Multicast bzw. `n−1` Unicasts | nativer **`broadcast(Message)`** |
| Startup | READY/GO-Barriere nötig (asynchrone Prozesse, Socket-Bind) | **entfällt** – Mailboxen puffern; Knoten 0 injiziert das Token sofort |
| Verlust/Stall | `stalled` möglich (kein Retransmit) | praktisch nie → `status=ok` |
| Geteilter Zustand | `volatile`/`Atomic` (zwei Threads pro Knoten) | knotenlokal, **keine** Synchronisierung nötig |
| Zeitmessung | `System.nanoTime()` (real) | `System.nanoTime()` (**real**, kein `sleep()` im Ring) |

Die Vereinfachung ist erheblich: keine Sockets, keine Multicast-Interface-Wahl, keine
Membership-Tabelle, keine Firewall, kein Startbarrier. Der gesamte Ring ist **eine** Java-Datei.

---

## Bauen & Starten

**Voraussetzung: JDK 25** (sim4da.jar ist Java-25-Bytecode, class major 69). Hier ist
**Temurin 25** installiert (`winget install EclipseAdoptium.Temurin.25.JDK`); Gradle erkennt es
über seine Toolchain-Auto-Detection automatisch – kein `gradle.properties` nötig. `run_sim.py`
sucht das JDK 25 ebenfalls selbst (unter `C:\Program Files\Eclipse Adoptium\jdk-25*`), per
`--java <pfad>` überschreibbar.

Einzellauf:
```powershell
cd "Aufgabe 3\udp-fireworks-simulated"
.\gradlew.bat run --args="4 0.5 3"     # n=4, p0=0.5, k=3
# -> SUMMARY n=4 status=ok rounds=… multicasts=… rt_*_ms=… p0=0.5 k=3 mode=sim
```
Argumente: `RingSimulation <n> <p0> <k>`.

Experiment-Reihe (n-Rampe, `results/results.csv` + Plots):
```powershell
python scripts\run_sim.py                      # rampt 2,4,8,… bis zum Limit
python scripts\run_sim.py --n-list 2,4,8,16,32,64,128,256
python scripts\run_sim.py --jvm-opts "-Xss220k -Xmx3g"   # Limit hochschrauben
```
Das Script startet **einen** JVM-Prozess pro `n` (in-process), fängt die `SUMMARY`-Zeile ab und
schreibt dieselben Spalten wie Aufgabe 1 (`n,repeat,status,rounds,multicasts,rt_min_ms,rt_mean_ms,rt_max_ms`).

---

## Statistik-Format

`SUMMARY n=<n> status=ok rounds=<Token-Runden> multicasts=<gesendete Raketen> rt_min_ms rt_mean_ms rt_max_ms p0 k mode=sim`

- **rounds** = abgeschlossene Token-Umläufe bis zur Terminierung.
- **multicasts** = kumulierte Feuerwerke (`fwTotal` aus dem Token; entspricht bei `broadcast()`
  genau einem Broadcast-Ereignis je Rakete).
- **rt_\*_ms** = real gemessene Rundenzeit (Token von Knoten 0 los bis zurück), in ms.

---

## Ergebnisse (dieser Rechner)

JVM-Optionen `-Xss256k -Xmx2g`, `p0=0.5`, `k=3` (gemittelt 1 Lauf je n):

| n | Token-Runden | Multicasts | rt_min (ms) | rt_mean (ms) | rt_max (ms) |
|----:|----:|----:|----:|----:|----:|
| 2 | 7 | 3 | 0.150 | 2.651 | 17.243 |
| 8 | 7 | 10 | 0.639 | 3.559 | 19.732 |
| 32 | 6 | 28 | 1.696 | 6.065 | 23.066 |
| 128 | 10 | 126 | 1.324 | 11.729 | 76.498 |
| 256 | 11 | 242 | 1.054 | 19.383 | 160.157 |
| 512 | 11 | 503 | 1.635 | 41.479 | 330.045 |
| 1024 | 13 | 1084 | 2.582 | 81.554 | 688.468 |
| 2048 | 16 | 1955 | 3.806 | 234.045 | 2083.326 |
| 4096 | 19 | 4148 | 8.650 | 805.074 | 8297.895 |
| **8192** | 16 | 8108 | 19.540 | 3371.825 | 27152.622 |

**Maximales n = 8192.** Bei `n=16384` lief eine Runde so lange, dass der 90-s-Watchdog griff –
die Grenze ist hier also **die mit n linear wachsende Rundenzeit** (n Hops + Kontextwechsel
tausender Threads in einer JVM), **nicht** ein hartes Thread-Limit. Mit kleinerem `-Xss` und
längerem Watchdog ginge n noch höher, aber die Rundenzeiten werden unpraktikabel (Sekunden bis
Minuten je Umlauf).

---

## Vergleich & Interpretation (Aufgabe 1 vs. 2 vs. 3)

**Maximales n.** Aufgabe 1 (localhost, **ein Prozess je Knoten**) erreichte hier **n = 256** –
begrenzt durch Prozess-/Port-/Socket-Ressourcen. Aufgabe 3 (**ein Thread je Knoten, eine JVM**)
erreicht **n = 8192** (≈ 32×), weil Threads viel billiger sind als Prozesse und es keine Ports/
Sockets gibt. Aufgabe 2 (**reale Rechner**) ist durch die Anzahl verfügbarer Geräte begrenzt
(typisch n = 2–wenige).

**Rundenzeit.** Bei gleichem n ist die Simulation pro Runde **deutlich schneller** als das echte
UDP auf localhost – z. B. bei `n=256`: **≈ 19 ms** (sim) vs. **≈ 782 ms** (Aufgabe 1, localhost),
weil In-Process-Mailbox-Passing den OS-Netzwerkstack (Multicast + Unicast) umgeht. Verteilt
(Aufgabe 2) dominiert dagegen die **WLAN-RTT** (ms je Hop) und Jitter. Gemeinsam ist allen
Varianten: die Rundenzeit wächst ~linear mit n (n Hops pro Umlauf).

**Zuverlässigkeit / Konsistenz.** sim4da-Kanäle sind verlustfrei und FIFO – deshalb gibt es hier
kein `stalled` und keinen Retransmit-Bedarf (Coulouris §4.2.1). Das macht die Simulation stabil und
reproduzierbar, blendet aber genau die Fehlersemantik aus, die Aufgabe 4 (Konsistenz) untersucht.

**Aufwand.** Implementierung: Aufgabe 3 ist mit Abstand am kürzesten (eine Datei, nativer
`broadcast()`, kein Socket-/Membership-/Firewall-/Barrier-Code). Experiment: ein Prozess je Lauf,
kein Port-Management, kein Geräte-Setup – ebenfalls am einfachsten. Aufgabe 2 ist am aufwändigsten
(Adressverwaltung, Firewall, manuelles Starten je Gerät, reale Netz-Tücken wie Client-Isolation).

---

## Dateien
- `src/firework/RingSimulation.java` – der simulierte Ring (Kern, eine Datei).
- `scripts/run_sim.py` – n-Rampe + `results.csv` + Plots.
- `build.gradle.kts`, `settings.gradle.kts`, `lib/sim4da.jar`.
- `results/` – `results.csv`, `rounds_vs_n.png`, `multicasts_vs_n.png`, `roundtime_vs_n.png`.
