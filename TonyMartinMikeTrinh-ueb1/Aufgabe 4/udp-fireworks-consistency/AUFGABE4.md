# Aufgabe 4 – Konsistenz

Erweiterung der Aufgabe-3-Simulation (Feuerwerk-Token-Ring in **sim4da**) um Mechanismen, die
**inkonsistente Sichten erkennen, melden und vermeiden**. Beantwortet die Leitfrage: *Geht in der
Simulation alles mit rechten Dingen zu, oder können Knoten eine inkonsistente Sicht haben?*

> Literatur: logische Uhren *Coulouris §14.4 / Van Steen §6.2*; Multicast-Ordering & Agreement
> *Coulouris §15.4 / Van Steen §4.4*; Konsistenzmodelle *Van Steen Kap. 7*; UDP-Omission
> *Coulouris §4.2.1*; Ring/Token *Coulouris §15.2*.

---

## Konsistenzkriterien

| | Kriterium | Bedeutung |
|---|---|---|
| **K1** | Token-Konsistenz | Genau *ein* Token; `lap`-Nummern bei jedem Knoten **monoton steigend**. |
| **K2** | Agreement Feuerwerke | Am Ende kennt **jeder** Knoten dieselbe Raketenzahl = `fwTotal` des Tokens. |
| **K3** | Kausale Terminierung | Das `Stop`-Signal überholt **kein** Feuerwerk; niemand endet mit ausstehendem Fire. |

## Mechanismen

- **Lamport-Uhren** in jedem Knoten (`++lamport` bei Ereignis/Send, `max(.,ts)+1` bei Empfang); jede
  Nachricht trägt `ts` → kausaler Ordnungsrahmen.
- **Erkennen & Melden:** K1 wird **lokal** an jedem Knoten geprüft (`INCONSISTENCY …type=lap_order`).
  K2 wird durch eine **zweiphasige Terminierung** global geprüft: nach `k` stillen Runden zirkuliert
  Knoten 0 ein **`Finalize`-Token** durch den Ring, jeder Knoten hängt seine beobachtete Zählung an;
  zurück bei Knoten 0 werden alle Werte gegen `fwTotal` verglichen → **`CONSISTENCY`-Zeile**.
- **Vermeiden:** Die Sammlung über den **FIFO-Ring** (statt eines *racing* `Stop`-Broadcasts)
  garantiert K3 — dank FIFO hat jeder Knoten beim `Finalize` alle vorher zugestellten Feuerwerke
  schon verarbeitet. Erst danach broadcastet Knoten 0 `Stop`. ⇒ Sicht **konsistent by construction**.
- **Demonstration:** `--inject-loss p` verwirft FIRE-Beobachtungen mit Wahrscheinlichkeit `p`
  (simuliert UDP-Omission aus Aufgabe 1/2) → Detektor meldet `verdict=INCONSISTENT`.

## Bauen & Starten

> Braucht **JDK 25** (Temurin 25 installiert, Gradle erkennt es automatisch).

```powershell
cd "Aufgabe 4\udp-fireworks-consistency"
.\gradlew.bat run --args="8 0.5 3"          # n=8, p0=0.5, k=3, loss=0  -> verdict=ok
.\gradlew.bat run --args="64 0.5 3 0.2"     # 4. Arg = lossP            -> verdict=INCONSISTENT
python scripts\run_sim.py                                  # Sweep (loss=0)
python scripts\run_sim.py --n-list 8,32,64,128,256 --inject-loss 0.2
```
Argumente: `RingSimulation <n> <p0> <k> [lossP]`.

## Output-Format

```
CONSISTENCY verdict=ok fwTotal=11 observed_min=11 observed_max=11 disagreeing=0/8 lap_violations=0 lamport_max=129 loss=0.00
SUMMARY n=8 status=ok rounds=6 multicasts=11 rt_*_ms=… p0=0.5 k=3 mode=sim consistency=ok
```
- `verdict` = `ok`, wenn **alle** Knoten genau `fwTotal` beobachtet haben (K2 erfüllt).
- `observed_min/max` = Spannweite der knotenlokalen Zählungen; bei `ok` gilt `min==max==fwTotal`.
- `disagreeing=d/n` = Anzahl Knoten, deren Sicht von `fwTotal` abweicht.

---

## Ergebnisse (dieser Rechner)

**Zuverlässige Kanäle (`loss=0`):** durchgängig konsistent — jeder Knoten sieht exakt `fwTotal`.

| n | fwTotal | observed | verdict |
|----:|----:|:--:|:--:|
| 2 | 4 | 4..4 | ok |
| 32 | 33 | 33..33 | ok |
| 256 | 261 | 261..261 | ok |
| 1024 | 1062 | 1062..1062 | ok |

**Fehlerinjektion (`--inject-loss 0.2`, simuliert UDP-Omission):** durchgängig erkannt.

| n | fwTotal | observed (min..max) | disagreeing | verdict |
|----:|----:|:--:|:--:|:--:|
| 8 | 9 | 7..8 | 8/8 | INCONSISTENT |
| 32 | 31 | 22..28 | 32/32 | INCONSISTENT |
| 64 | 58 | 40..53 | 64/64 | INCONSISTENT |
| 128 | 128 | 91..117 | 128/128 | INCONSISTENT |
| 256 | 256 | 187..221 | 256/256 | INCONSISTENT |

(CSV: `results/results.csv`, `results/results_loss0.2.csv`.)

## Interpretation — „geht alles mit rechten Dingen zu?"

**In der Simulation: ja.** Weil sim4da-Kanäle **zuverlässig und FIFO** sind und die Terminierung erst
nach `k` stillen Runden (Quieszenz) erfolgt, ist jedes Feuerwerk überall zugestellt, bevor `Finalize`
und `Stop` eintreffen. Der Agreement-Check bestätigt das empirisch (K2 = ok bis n = 1024); K1 wird
nie verletzt (ein Token + FIFO); K3 ist durch die zweiphasige Terminierung garantiert.

**In Aufgabe 1/2 (echtes UDP): nicht automatisch.** UDP ist *best-effort* — FIRE-Multicasts können
verloren gehen oder von `STOP` überholt werden. Genau das reproduziert `--inject-loss`: schon bei
20 % Verlust hat **kein** Knoten mehr die korrekte Sicht. Der hier gebaute Detektor würde solche
realen Inkonsistenzen melden; **vermeiden** ließen sie sich dort nur durch zuverlässige, geordnete
Auslieferung (ACK/Retransmit bzw. geordnetes Multicast, *Coulouris §15.4*) — also genau die
Eigenschaften, die der Simulator schon mitbringt.

---

## Dateien
- `src/firework/RingSimulation.java` — Lamport-Uhren, zweiphasige Terminierung, Agreement-Check, Injektion.
- `scripts/run_sim.py` — Sweep + Konsistenz-Parsing + `--inject-loss`.
- `results/` — `results.csv` (loss=0), `results_loss0.2.csv`.
