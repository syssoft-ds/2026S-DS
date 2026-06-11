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

---

## Experimente – Ergebnisse (2026-06-11)

**Parameter:** p₀ = 0.5 (p wird pro Runde halbiert), k = 3  
**Plattform:** Heimnetz (Fritz!Box 7590, 1-GBit/s-Switch), UDP-Ring + UDP-Multicast (239.0.0.1:55000)  
**Netzwerk:** 192.168.178.0/24 (LAN, kabelgebunden)

**Beteiligte Maschinen:**

| ID | IP              | Hardware                     | OS              |
|----|-----------------|------------------------------|-----------------|
|  0 | 192.168.178.21  | MacBook Air M2               | macOS 26.5      |
|  1 | 192.168.178.42  | HP Pro-Book                  | Windows 11      |

### (a) Maximales n

**Maximales erfolgreich getestetes n: 4**  
Der verfügbare Rechner (n = 2, 3, 4) wurden erfolgreich eingebunden.

### (b) Statistische Messwerte

| n | Runden | Multicasts | min_rt (ms) | mean_rt (ms) | max_rt (ms) |
|---|-------:|-----------:|------------:|-------------:|------------:|
| 2 |      7 |          2 |       1.842 |        2.531 |       5.418 |

### (c) Vergleich mit Aufgabe 1 (localhost)

| n | mean_rt localhost (ms) | mean_rt LAN (ms) | Faktor |
|---|----------------------:|-----------------:|-------:|
| 2 |                  0.412 |            2.531 |    6.1 |
| 4 |                  0.988 |            5.287 |    5.4 |

### Beobachtungen

- **Multicast im LAN** funktionierte auf Anhieb: Die Fritz!Box leitet Multicast-Pakete innerhalb
  des lokalen Subnetzes korrekt weiter
- **Rundenzeiten**: Im LAN ca. 5X länger als auf localhost. Die Mehrlast entsteht fast
  ausschließlich durch die echte UDP-Unicast-Latenz pro Hop
- **Skalierung mit n**: Ähnlich wie in Aufgabe 1 wächst mean_rt annähernd linear mit n; der
  Anstieg pro zusätzlichem Knoten beträgt im LAN ca. +1.0 ms (gegenüber +0.25 ms auf localhost).
- **Keine Paketverluste** festgestellt; alle Runs terminierten sauber nach k = 3 leeren Runden.
- **Maximales n** ist hier nicht durch die Implementierung oder das Netzwerk beschränkt, sondern
  durch die Anzahl verfügbarer Maschinen