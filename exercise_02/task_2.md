Führen Sie die Anwendung aus Aufgabe 1 auf möglichst vielen realen Computersystemen aus. Auf jedem Computer soll nur ein
Prozess aus dem logischen Ring ausgeführt werden. Bilden Sie weiterhin – nach Möglichkeit – die Broadcasts auf UDP-Multicast-
Nachrichten ab. Wenn das nicht gehen sollte, können Sie alternativ n-1 Unicast-Nachrichten an die anderen Prozesse senden.
Ermitteln Sie analog zu Aufgabe 1 das maximale n, das Sie erreichen (hängt vermutlich primär davon ab, zu wieviel anderen
Computersystemen Sie Zugang haben; ggf. überzeugen Sie Menschen aus Ihrem Umfeld, an diesem hochinteressanten Experiment
teilzunehmen) sowie ebenfalls die in Aufgabe 1 gemessenen statistischen Informationen (insbesondere minimale, mittlere und
maximale Rundenzeit).

---

## Implementierung

Jeder Prozess läuft auf einem eigenen Rechner und wird mit expliziten Peer-Adressen konfiguriert:

```bash
# 1. peers.txt anlegen (eine ip:port-Zeile pro Rechner, in Ring-Reihenfolge)
# 2. Kommandos anzeigen (auf einem beliebigen Rechner):
./start_node.sh peers.txt

# 3. Auf jeder Maschine das passende Kommando ausführen.
#    Maschinen 1..n-1 zuerst starten, dann Maschine 0 (wartet --start-delay Sekunden).

# Beispiel: Knoten 1 direkt starten:
./start_node.sh peers.txt --id 1
```

**Wichtig bei Multicast im LAN:**
- Firewall muss UDP-Port 50000 (Ring) und 55000 (Multicast) freigeben.
- Falls Multicast geblockt ist: alternativ n-1 Unicast mit `--mcast-ttl 1` plus manueller Firework-Logik (nicht nötig, wenn Multicast funktioniert).
- Auf macOS ggf. `--mcast-iface <lokale-IP>` angeben (z.B. `--mcast-iface 192.168.1.10`).

---

## Experimente – Ergebnisse

**Parameter:** p₀ = 0.5 (p wird pro Runde halbiert), k = 3  
**Plattform:** Echtes LAN, UDP-Ring + UDP-Multicast (239.0.0.1:55000)  
**Netzwerk:** _[eintragen: z.B. Uni-WLAN, Heimnetz, ...]_

### (a) Maximales n

**Maximales erfolgreich getestetes n:** _[eintragen]_

### (b) Statistische Messwerte

| n | Runden | Multicasts | min_rt (ms) | mean_rt (ms) | max_rt (ms) |
|---|-------:|-----------:|------------:|-------------:|------------:|
| _  | _      | _          | _           | _            | _           |

### (c) Vergleich mit Aufgabe 1 (localhost)

| n | mean_rt localhost (ms) | mean_rt LAN (ms) | Faktor |
|---|----------------------:|----------------:|-------:|
| _ | _                      | _               | _      |

### Beobachtungen

_[Nach Durchführung der Experimente ausfüllen]_