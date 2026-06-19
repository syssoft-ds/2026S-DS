# Aufgabe 1 — Entwicklungs- und Ergebnisbericht

Dokumentation dessen, was für **Aufgabe 1 ("Ein Feuerwerk an UDP-Nachrichten", pseudo-verteilt)**
gebaut, getestet und gemessen wurde. Dient als Arbeitsprotokoll und als Grundlage für den
abzugebenden Bericht.

> **Literatur** (verpflichtend, vgl. Projektkonventionen): Ring-Koordination
> *Coulouris §15.2 (Ring-based algorithm)*; IP-Multicast *Coulouris §4.4.1 / Van Steen §4.4*;
> UDP-Datagramme & Fehlermodell (Verlust/Omission) *Coulouris §4.2.1 / Van Steen §4.2*.

---

## 1. Aufgabenstellung (Kurzfassung)

`n` Prozesse bilden einen logischen Ring (Overlay). Ein **Token ("Streichholz")** kreist. Wer es
hält, **zündet mit Wahrscheinlichkeit `p` eine Rakete** (Broadcast an alle) und reicht das Token
weiter. Jeder Knoten **halbiert `p` pro Durchlauf** (`p = p/2`). Terminierung, wenn in `k`
aufeinanderfolgenden Runden **niemand** gezündet hat.

Pflichten: **Unicast-UDP** im Ring, **Broadcasts auf UDP-Multicast**, alle `n` Prozesse auf
`localhost` als **echte OS-Prozesse**, **Skript** zur Automatisierung wachsender Ringe; ermitteln:
(a) maximales `n`, (b) Statistik je `n` (Token-Runden, gesendete Multicasts, min/mittlere/max
Rundenzeit).

**Abgrenzung:** `sim4da-S26-pingpong/` gehört zu **Aufgabe 3** (Simulator). Aufgabe 1 ist hier
bewusst **ohne sim4da**, mit reinem `java.net` (`DatagramSocket` + `MulticastSocket`) umgesetzt.

---

## 2. Architektur

`n` JVMs auf localhost, je eine = ein Ring-Knoten mit `rank ∈ 0..n-1`. **Knoten 0 = Initiator.**

```
TOKEN (Unicast UDP):   0 ──▶ 1 ──▶ 2 ──▶ … ──▶ (n-1) ──▶ 0 ──▶ …   (eine Runde = ein voller Umlauf)
FIRE/Steuerung (Multicast UDP, Gruppe 230.0.0.1:4446):  jeder Knoten ist Gruppenmitglied
```

Pro Knoten:
- **Unicast `DatagramSocket`** an `127.0.0.1:basePort+rank` — empfängt das Token vom Vorgänger.
- **`MulticastSocket`** in der Gruppe (`SO_REUSEADDR`, Loopback aktiv) — empfängt/sendet Feuerwerke
  und Steuer-Nachrichten.
- Nachfolger-Port selbst berechnet: `basePort + ((rank+1) mod n)`.

**Zwei Threads pro Knoten** (+ Follower: ein kurzlebiger READY-Announcer):
1. **Token-/Hauptthread** — blockiert auf dem Unicast-Socket, führt den Token-Zug aus, leitet weiter.
2. **Multicast-Listener** — behandelt `GO`, `FIRE`, `STOP`.

Quelle: `src/firework/RingNode.java`.

---

## 3. Protokoll (UTF-8-Text, 1 Datagramm = 1 Nachricht)

| Nachricht | Kanal | Bedeutung |
|---|---|---|
| `TOKEN <lap> <fired> <fwTotal>` | Unicast | `lap` = Runde (von Knoten 0 verwaltet); `fired` = 0/1 (zündete in dieser Runde jemand, OR-akkumuliert); `fwTotal` = kumulierte Raketen |
| `READY <rank>` | **Unicast an Knoten 0** | Startbarriere (siehe §7) |
| `GO` | Multicast | Knoten 0: Ring steht, Token startet |
| `FIRE <rank> <lap>` | Multicast | eine gezündete Rakete (der eigentliche Broadcast) |
| `STOP` | Multicast | Knoten 0: Terminierung, alle herunterfahren |

---

## 4. Knoten-Lebenszyklus

1. Sockets binden + Multicast-Gruppe beitreten (Loopback bevorzugt, `IP_MULTICAST_LOOP=true`, `TTL`).
2. Multicast-Listener starten.
3. **Startbarriere:** Follower senden periodisch `READY` (Unicast) an Knoten 0; Knoten 0 sammelt,
   bis alle `n` da sind (sich selbst eingeschlossen).
4. **Knoten 0** verkündet `GO` (Multicast) und injiziert das Token.
5. **Token-Zug** (jeder Knoten): mit Wahrscheinlichkeit `p` zünden → `FIRE` multicasten, Token
   markieren (`fired=1`, `fwTotal++`); dann `p = p/2`; Token an Nachfolger weiterleiten.
6. **Rundenabrechnung (Knoten 0):** Token-Rückkehr = Rundenende → Dauer messen, `fired` prüfen
   (`consecutiveQuietLaps`), bei `>= k` terminieren, sonst neue Runde (eigener Zug, weiterleiten).
7. **Terminierung:** `STOP` (Multicast, mehrfach) + `SUMMARY`-Zeile; Follower fahren bei `STOP`
   herunter.

---

## 5. Statistik

Knoten 0 leitet alles aus dem Token + eigenen Timern ab und gibt **eine maschinenlesbare Zeile** aus:

```
SUMMARY n=8 status=ok rounds=6 multicasts=7 rt_min_ms=1.898 rt_mean_ms=39.624 rt_max_ms=189.027 p0=0.5 k=3
```

- **Token-Runden** = abgeschlossene Umläufe.
- **Multicasts** = `fwTotal` (kumulierte Feuerwerke; Steuer-Multicasts READY/GO/STOP zählen nicht).
- **rt_min/mean/max** = aus den per-Runde-Dauern (`System.nanoTime`), in ms, `Locale.US` (Dezimalpunkt
  → stabiles CSV-Parsing).

Mit `--verbose-nodes` schreibt jeder Knoten `logs/node-<rank>.log` (gesendete/empfangene Nachrichten,
beobachtete Feuerwerke) — Grundlage für die Konsistenzanalyse in **Aufgabe 4**.

---

## 6. Orchestrator (`scripts/run_experiments.py`, Python + matplotlib)

- Baut das Projekt (`gradlew compileJava`), startet je `n` die Knotenprozesse via
  `java -cp build/classes/java/main firework.RingNode …`.
- Modi: feste `--n-list 2,4,8,…` **oder** Ramp (Verdopplung bis zum Fehlschlag → ermittelt max n).
- **Watchdog** pro Lauf; Ergebnis → `results/results.csv`; Plots `rounds_vs_n.png`,
  `multicasts_vs_n.png`, `roundtime_vs_n.png`; Tabelle + max n auf stdout.
- Optionen: `--repeats --p0 --k --base-port --mc-addr --mc-port --idle-timeout-ms --ttl --timeout
  --jvm-opts --verbose-nodes --no-build --no-plots --max-n-cap`.

---

## 7. Robustheit: Hybrid-Ansatz + Mess-Robustheiten

**Designentscheidung (mit Betreuung der Lernziele konsistent):** Das Token bleibt **pures UDP** —
**kein Retransmit**. Geht das eine Token-Datagramm verloren, endet der Lauf. Damit ist UDP-Verlust
sichtbar (wichtig für Aufgabe 4). Zombie-Vermeidung: jeder Knoten beendet sich bei Leerlauf-Timeout
selbst, das Skript killt hängende Läufe per Watchdog. Ein so beendeter Lauf zählt als Fehlschlag —
genau das macht „maximales n, das gerade noch läuft" messbar.

Damit aber wirklich der **Ring** (Token-Verlust/Ressourcen) und nicht die **Messumgebung** das Limit
setzt, kamen drei Robustheiten hinzu, **ohne** die UDP-Semantik des Tokens zu verändern:

1. **Startbarriere per Unicast** statt Multicast (O(n) statt O(n²)-Flut).
2. **Timeouts skalieren mit n** + Sonderbehandlung der warmup-lastigen ersten Runde; Follower lösen
   Idle-Exit erst nach dem ersten verarbeiteten Token aus.
3. **Freier-Port-Scan** (Skript) + **Bind-Retry** (Knoten) gegen belegte Ports.

---

## 8. Chronologie: Probleme und Fixes

Die Implementierung war lauffähig, aber das **echte** max n zu messen erforderte mehrere Iterationen.
Jeder Schritt trennte ein **Mess-Artefakt** vom **echten Ringverhalten**:

| # | Symptom | Ursache | Fix |
|---|---|---|---|
| 1 | `SocketException: Socket closed` beim ersten `STOP` → keine SUMMARY | Knoten 0 empfängt sein **eigenes** `STOP` per Loopback → Listener schließt den Socket mitten im Burst | `stopping`-Flag: eigenes STOP ignorieren; `terminate()` toleriert Sendefehler und gibt SUMMARY immer aus |
| 2 | `startup_failed` ab n≈64 | **Multicast-READY ist O(n²)** → Empfangspuffer überlaufen, Barriere nie vollständig | READY auf **Unicast an Knoten 0** umgestellt; zusätzlich `SO_RCVBUF = 1 MiB` |
| 3 | `stalled` (rounds=0) ab n≈64/128 | **Idle-Timeout (3 s) < Rundenzeit**; erste Runde durch JVM-Warmup besonders langsam | Knoten 0: erste Runde mit Startup-Budget, danach straff; Follower: Idle-Exit erst nach erstem Token; Skript skaliert `idleTimeoutMs` mit n |
| 4 | `BindException: Address already in use` (z. B. Rang 50) → `startup_failed` | Fixer `basePort` kollidiert mit belegten Ports / Restprozessen | **Freier-Port-Scan** je Lauf + **Bind-Retry** im Knoten |
| 5 | Thread-Review-Befund | `BufferedWriter` wird beim Shutdown ohne den `logLine`-Monitor geschlossen → Data Race (nur `verbose`) | Schließen unter demselben `synchronized(this)`, danach `logWriter=null` |

Nach #1–#5: alle Läufe `n=2…256` stabil `status=ok`.

---

## 9. Thread-Safety-Review (Skill `thread-review`)

Geteilter Zustand über Token-Thread / Multicast-Listener / READY-Announcer:

- `running`, `ringActive`, `stopping` — `volatile` Einweg-Flags → korrekt.
- `fireworksObserved` — `AtomicInteger` (korrekt; aktuell nur für Aufgabe 4 vorgesehen).
- `readyRanks` (HashSet) — nach dem Umbau **nur** Initiator-Thread → safe.
- `shutdownDone` — stets unter `shutdownLock` → safe; kein I/O unter Lock, keine verschachtelten Locks
  → kein Deadlock.
- Sockets — vor `Thread.start()` zugewiesen (happens-before); `close()` idempotent.
- **Ein behobener Befund:** `logWriter`-Race beim Shutdown (siehe §8 #5).

**Verdikt:** safe to submit (keine kritischen Races/Deadlocks).

---

## 10. Ergebnisse (Beispiel-Testrechner, Windows 11, p0=0.5, k=3)

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

**Maximal erfolgreich getestetes n = 256.**

Interpretation:
- **Multicasts** wachsen ~linear mit n — mehr Knoten = mehr Zündchancen pro Runde.
- **rt_min** (Steady-State) wächst ~linear mit n: eine Runde = n Unicast-Hops auf localhost.
- **rt_max** wird von der **ersten Runde** dominiert (JVM-Warmup aller Prozesse, GO-Verteilung,
  Scheduling von n gleichzeitigen JVMs) — bis ~11 s bei n=256.
- **Token-Runden** bleiben moderat (≈3–11): durch `p = p/2` versiegen die Feuerwerke nach wenigen
  Runden, danach beenden `k` stille Runden den Lauf.
- Jenseits ~128 werden Rundenzeiten sekundenlang (der Rechner sättigt) — die Grenze ist hier primär
  **Ressourcen/Scheduling**, nicht Paketverlust.

Rohdaten: `results/results.csv`; Plots: `results/*.png`.

---

## 11. Dateien

```
Aufgabe 1/udp-fireworks/
├── build.gradle.kts, settings.gradle.kts   # Gradle, Java 17, keine externen Deps
├── gradlew(.bat), gradle/wrapper/…         # Wrapper (Gradle 9.4.1, aus sim4da übernommen)
├── src/firework/RingNode.java              # der Knoten (Kernlogik)
├── scripts/run_experiments.py              # Orchestrator + Plots
├── requirements.txt                        # matplotlib
├── README.md                               # Build/Run/Ergebnisse
├── ENTWICKLUNG.md                          # dieses Dokument
└── results/                                # results.csv + PNGs (gitignored)
```

---

## 12. Ausführen

```bash
cd "Aufgabe 1/udp-fireworks"
./gradlew build
python -m pip install -r requirements.txt
python scripts/run_experiments.py --n-list 2,4,8,16,32,64,128,256
# harte Grenze weiter ausloten:
python scripts/run_experiments.py --max-n-cap 1024 --timeout 300
# Einzelring mit Logs (für Aufgabe 4):
python scripts/run_experiments.py --n-list 4 --verbose-nodes
```

Voraussetzungen: JDK 17 im PATH, Python 3.9+ (matplotlib nur für Plots).

---

## 13. Offene Punkte / nächste Aufgaben

- **Aufgabe 2** — verteilt über mehrere reale Rechner (Multicast bzw. n-1 Unicasts).
- **Aufgabe 3** — Simulation mit sim4da (`OneRingToRuleThemAll`); Vergleich der Aufwände/Resultate.
- **Aufgabe 4** — Konsistenz: Sehen alle Knoten dieselben Feuerwerke in derselben Reihenfolge?
  Die per-Node-Logs (`--verbose-nodes`) sind dafür vorbereitet; sinnvoll wären logische Uhren
  (*Coulouris §14.4 / Van Steen §6.2*), da `System.nanoTime` über Prozessgrenzen nicht vergleichbar ist.
- **`fireworksObserved`** in die SUMMARY aufnehmen (empfangene vs. gesendete Multicasts) — direkt
  nützlich als Konsistenz-Indikator.
