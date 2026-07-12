# Aufgaben 1 & 2 – Ein Feuerwerk an UDP-Nachrichten

## Dateien

| Datei | Zweck |
|---|---|
| `aufgabe_1.py` | Aufgabe 1 (pseudo-verteilt): Ein Ring-Prozess auf localhost |
| `start_ring_1.py` | Manueller Starter für einen einzelnen Ring mit sichtbaren Ausgaben |
| `run_experiments.py` | Automatisierte Experimente mit wachsendem n (2, 4, 8, …), schreibt `results_aufgabe1.csv` |
| `aufgabe_2.py` | Aufgabe 2 (verteilt): ein Prozess pro Rechner, Topologie aus Peers-Datei |
| `peers.txt.example` | Vorlage für die Ring-Topologie von Aufgabe 2 |

## Verwendung

```bash
# Einzelner Ring mit Live-Ausgabe (n=4, p=0.5, k=3)
python start_ring_1.py --n 4 --p 0.5 --k 3

# Automatisierte Experimentreihe
python run_experiments.py --p 0.5 --k 3 --max-n 1024 --repeats 3
```

## Design

- **Ring**: Prozess i bindet UDP-Port `20000 + i` auf 127.0.0.1 und sendet das
  Token an Port `20000 + (i+1) mod n`. (Basis-Port 20000, weil unter Windows
  Ports um 5000 teilweise von Systemdiensten exklusiv belegt sind, z.B. UDP 5050.)
- **Zündwahrscheinlichkeit**: p ist *lokaler Zustand jedes Prozesses* und wird
  bei jedem Token-Durchlauf halbiert (wie im Aufgabenblatt: „Jeder Prozess
  reduziert … seine Zündwahrscheinlichkeit").
- **Broadcast → Multicast**: Feuerwerke gehen als UDP-Multicast an die Gruppe
  `224.1.1.1:6000`, der alle Prozesse beigetreten sind (`IP_MULTICAST_LOOP=1`,
  damit lokale Prozesse die Nachrichten erhalten).
- **Terminierung (Konsistenz!)**: Ob in einer Runde gezündet wurde, wird über
  ein `fired`-Flag entschieden, das *im Token mitreist* und von jedem zündenden
  Prozess gesetzt wird. P0 zählt damit stille Runden deterministisch – die
  asynchron eintreffenden Multicasts werden nur „angeschaut", nicht für die
  Terminierungsentscheidung benutzt (Multicasts könnten zum Entscheidungs-
  zeitpunkt noch unterwegs sein → inkonsistente Sicht, siehe Aufgabe 4).
- **Statistik-Einsammlung**: Bei Terminierung schickt P0 ein STOP durch den
  Ring; jeder Prozess addiert seine gesendeten Multicasts auf einen Zähler in
  der STOP-Nachricht. P0 gibt am Ende eine `SUMMARY`-Zeile aus (Runden,
  Multicasts gesamt, min/avg/max Rundenzeit).
- **Experiment-Handshake**: `run_experiments.py` wartet pro Prozess auf ein
  `READY` (Sockets gebunden), sendet dann `GO` an P0 und empfängt am Ende die
  `SUMMARY` per UDP. Timeouts ⇒ Experiment gescheitert ⇒ das zuletzt
  erfolgreiche n ist das maximal erreichbare n.

## Nachrichtenformate (JSON über UDP)

```
TOKEN    {"type": "TOKEN", "round": r, "fired": bool}     # Unicast an Nachfolger
FIREWORK {"type": "FIREWORK", "sender": i, "round": r}    # Multicast an alle
STOP     {"type": "STOP", "total_multicasts": m}          # Unicast, sammelt Zähler ein
READY/GO/SUMMARY                                          # Handshake mit run_experiments.py
```

---

# Aufgabe 2 – Verteilt (ein Prozess pro Rechner)

## Deployment

1. `aufgabe_2.py` und eine **auf allen Rechnern identische** `peers.txt` verteilen
   (Vorlage: `peers.txt.example`). Eine Zeile `host:port` pro Rechner,
   Zeilenreihenfolge = Ringreihenfolge, Zeilenindex (ab 0) = Prozess-ID.
2. Firewall: eingehendes UDP auf dem Token-Port (und Multicast-Port 22999)
   erlauben, unter Windows z.B.:
   ```powershell
   New-NetFirewallRule -DisplayName "DS Ring" -Direction Inbound `
       -Protocol UDP -LocalPort 20000,22999 -Action Allow
   ```
3. Erst die Prozesse 1 … n−1 starten, **P0 zuletzt** (P0 startet das Token nach
   `--start-delay` Sekunden, Default 3 s):
   ```bash
   # auf Rechner i (i = 1 … n-1):
   python aufgabe_2.py --id <i> --p 0.5 --k 3
   # auf Rechner 0 (zuletzt):
   python aufgabe_2.py --id 0 --p 0.5 --k 3
   ```
4. P0 gibt am Ende die `SUMMARY`-Zeile aus (Runden, Multicasts gesamt,
   min/avg/max Rundenzeit) – identische Statistik wie in Aufgabe 1.

## Multicast vs. Unicast-Fallback

- **Standard**: Feuerwerke gehen als UDP-Multicast an `239.1.2.3:22999`
  (administrativ begrenzter Bereich, `--ttl 1` ⇒ eigenes Subnetz). Das
  funktioniert nur, wenn alle Rechner im selben LAN/Subnetz hängen und das
  Netz Multicast zulässt.
- **Fallback** (`--unicast-fireworks` auf *allen* Prozessen): jedes Feuerwerk
  wird als n−1 Unicast-Nachrichten an alle anderen Prozesse geschickt; diese
  kommen auf dem Token-Socket an. Nötig z.B. bei WLAN-Client-Isolation
  (Eduroam!), über Subnetz-Grenzen oder VPN.

## Lokaler Funktionstest (ohne weitere Rechner)

```bash
# peers_local_test.txt: 3x 127.0.0.1 mit Ports 23000-23002
python aufgabe_2.py --id 1 --peers peers_local_test.txt &
python aufgabe_2.py --id 2 --peers peers_local_test.txt &
python aufgabe_2.py --id 0 --peers peers_local_test.txt
```

Beide Modi (Multicast und `--unicast-fireworks`) wurden so lokal verifiziert.
