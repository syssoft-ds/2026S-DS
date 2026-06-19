# Aufgabe 1 – Ein Feuerwerk an UDP-Nachrichten (Pseudo-Verteilt)

`n` Prozesse bilden einen logischen Ring auf `localhost`. Ein **Token** ("Streichholz")
kreist per **Unicast-UDP**; wer es hält, zündet mit Wahrscheinlichkeit `p` eine Rakete
(**UDP-Multicast** an alle) und halbiert anschließend `p`. **Knoten 0** ist Initiator:
er injiziert das Token, misst Rundenzeiten und terminiert, wenn in `k` aufeinanderfolgenden
Runden niemand gezündet hat.

> Literatur: Ring-Koordination *Coulouris §15.2*; IP-Multicast *Coulouris §4.4.1 / Van Steen §4.4*;
> UDP-Fehlermodell *Coulouris §4.2.1*.

## Aufbau

```
udp-fireworks/
├── build.gradle.kts        # application-Plugin, Java 17, keine externen Deps
├── settings.gradle.kts
├── src/firework/RingNode.java
├── scripts/run_experiments.py   # Orchestrator: rampt n, sammelt Statistik, plottet
├── requirements.txt        # matplotlib (nur fuer Plots)
└── results/                # results.csv + PNGs (gitignored)
```

## Voraussetzungen

- JDK 17 (installierte Runtime; `java` muss im PATH sein).
- Python 3.9+; für Plots: `python -m pip install -r requirements.txt`.

## Build

```
./gradlew build
```

## Einen einzelnen Ring manuell starten

Jeder Knoten ist ein eigener Prozess. Argumente:

```
java -cp build/classes/java/main firework.RingNode \
     <rank> <n> <basePort> <mcAddr> <mcPort> <p0> <k> [idleTimeoutMs] [ttl] [verbose] [startupTimeoutMs]
```

Beispiel `n=4` (vier Terminals; Knoten 0 zuletzt schadet nicht, die READY-Barriere
fängt Start-Races ab):

```
java -cp build/classes/java/main firework.RingNode 1 4 5000 230.0.0.1 4446 0.5 3 3000 1 true
java -cp build/classes/java/main firework.RingNode 2 4 5000 230.0.0.1 4446 0.5 3 3000 1 true
java -cp build/classes/java/main firework.RingNode 3 4 5000 230.0.0.1 4446 0.5 3 3000 1 true
java -cp build/classes/java/main firework.RingNode 0 4 5000 230.0.0.1 4446 0.5 3 3000 1 true
```

Knoten 0 gibt am Ende eine maschinenlesbare Zeile aus, z. B.:

```
SUMMARY n=4 status=ok rounds=12 multicasts=5 rt_min_ms=0.210 rt_mean_ms=0.640 rt_max_ms=2.110 p0=0.5 k=3
```

`verbose=true` schreibt zusätzlich `logs/node-<rank>.log` (gesendete/empfangene
Nachrichten) — Basis für die Konsistenzanalyse in Aufgabe 4. Für saubere Zeitmessung
in den Experimenten bleibt `verbose=false`.

## Experimente automatisiert

```
# feste Reihe:
python scripts/run_experiments.py --n-list 2,4,8,16,32

# automatische Verdopplung bis zum Fehlschlag (ermittelt max n):
python scripts/run_experiments.py
```

Erzeugt `results/results.csv` und die Plots `rounds_vs_n.png`, `multicasts_vs_n.png`,
`roundtime_vs_n.png`, druckt eine Tabelle und das maximale erfolgreiche `n`.

Wichtige Optionen: `--repeats`, `--p0`, `--k`, `--base-port`, `--mc-addr`, `--mc-port`,
`--idle-timeout-ms`, `--ttl`, `--timeout` (Watchdog), `--jvm-opts`, `--verbose-nodes`,
`--no-plots`, `--no-build`.

## Robustheit (Hybrid)

UDP ist unzuverlässig — geht das **eine** Token-Datagramm verloren, bleibt der Ring stehen.
Bewusste Entscheidung: **kein** Token-Retransmit (pure UDP-Semantik, wichtig für Aufgabe 4).
Stattdessen beendet sich jeder Knoten bei Leerlauf-Timeout (`idleTimeoutMs`) selbst (kein
Zombie), und das Script killt hängende Läufe per Watchdog. Ein so beendeter Lauf zählt als
Fehlschlag — genau das macht „maximales n, das gerade noch läuft" messbar.

Damit aber tatsächlich der **Ring** (Token-Verlust / Ressourcen) und nicht die Messumgebung das
Limit setzt, wurden drei reine Mess-Robustheiten ergänzt — sie verändern die UDP-Semantik des
Tokens nicht:

- **Startbarriere per Unicast:** READY geht direkt an Knoten 0 (O(n)) statt per Multicast an alle
  (O(n²) Flut, die bei großem n die Empfangspuffer überlief und die Barriere künstlich deckelte).
- **Timeouts skalieren mit n:** Rundenzeiten wachsen mit n (eine Runde = n Hops); die erste Runde
  enthält JVM-Warmup. Knoten 0 gibt der ersten Runde das großzügige Startup-Budget, danach gilt der
  straffe `idleTimeoutMs`; das Script skaliert `idleTimeoutMs` und Watchdog mit n. Follower lösen den
  Idle-Exit erst aus, nachdem sie das Token mindestens einmal verarbeitet haben.
- **Freier-Port-Scan:** Das Script sucht je Lauf einen freien, zusammenhängenden Portblock, damit von
  anderen Diensten/Restprozessen belegte Ports keine `BindException` (→ falsches `startup_failed`)
  erzwingen; zusätzlich bindet der Knoten mit kurzem Retry.

## Ergebnisse (Beispiel-Testrechner, Windows 11, p0=0.5, k=3)

| n | Token-Runden | Multicasts | rt_min (ms) | rt_mean (ms) | rt_max (ms) |
|---:|---:|---:|---:|---:|---:|
| 2 | 3 | 0 | 1.0 | 14.9 | 42.5 |
| 4 | 10 | 8 | 0.7 | 12.8 | 100.2 |
| 8 | 6 | 6 | 1.9 | 39.6 | 189.0 |
| 16 | 8 | 12 | 5.3 | 92.9 | 645.5 |
| 32 | 7 | 37 | 10.1 | 247.7 | 1459.6 |
| 64 | 11 | 57 | 20.6 | 258.8 | 2142.5 |
| 128 | 11 | 130 | 33.4 | 601.6 | 4955.3 |
| 256 | 10 | 231 | 112.9 | 1537.7 | 11164.0 |

**Maximal erfolgreich getestetes n = 256.** Gesendete Multicasts wachsen ~linear mit n (mehr Knoten
zünden). `rt_min` (Steady-State) wächst ~linear mit n (Runde = n Hops auf localhost). `rt_max` wird
von der ersten, warmup-lastigen Runde dominiert (bis ~11 s bei n=256). Jenseits ~128 werden die
Rundenzeiten sekundenlang (der Rechner sättigt bei n gleichzeitigen JVMs) — `--max-n-cap` und
`--timeout` erhöhen, um die harte Grenze weiter auszuloten. Werte je Lauf in `results/results.csv`,
Plots in `results/*.png`.

## Hinweis Multicast unter Windows

Der Knoten wählt automatisch ein multicast-fähiges Interface (Loopback bevorzugt, sonst
das erste reale IPv4-Interface) und aktiviert Loopback-Zustellung. `--ttl 0` hält Pakete
strikt host-lokal; `--ttl 1` ist robuster, falls nur ein reales Interface Multicast kann.
Falls keine Feuerwerke knotenübergreifend ankommen, `--ttl` bzw. `--mc-addr` anpassen.
