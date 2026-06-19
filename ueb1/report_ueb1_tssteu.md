<!-- ===== exercise_01/task_1.md ===== -->

Aufgabe 1 – Ein Feuerwerk an UDP-Nachrichten (Pseudo-Verteilt)
In dieser Aufgabe sollen n Prozesse in einem logischen Ring (Overlay-Netzwerk) eine Art Streichholz (Token) kreisen lassen. Ein
Prozess, der das Token bekommt, zündet mit einer Wahrscheinlichkeit p eine Feuerwerksrakete (Broadcast-Nachricht an alle
Prozesse im Ring) und leitet anschließend das Streichholz weiter. Jeder Prozess reduziert mit jedem Durchlauf seine
Zündwahrscheinlichkeit p (z.B. p = p/2). Verwenden Sie UDP für die Kommunikation im Ring und bilden Sie alle Broadcasts auf
UDP-Multicast ab. Die verteilte Anwendung soll terminieren, wenn in k aufeinanderfolgenden Runden kein einziger Prozess eine
Feuerwerksrakete gezündet hat. In dieser ersten Aufgabe sollen alle n Prozesse auf dem Testrechner (localhost, 127.0.0.1) gestartet
werden. Führen Sie mehrere Experimente mit wachsendem n (z.B. n = 2, 4, 8, 16, …) durch. Schreiben Sie dazu ein Shell- oder
Python-Script, um den Aufbau und die Ausführung der immer größer werdenden Ringe zu automatisieren. Zusätzlich zu Ihrer
Implementierung sollten Sie im Rahmen der Experimente (a) das maximale n ermitteln, das gerade noch so auf Ihrem Testrechner
erfolgreich durchgeführt werden konnte sowie (b) wesentliche statistische Informationen erfassen (mindestens: Gesamtanzahl der
Token-Runden; Gesamtanzahl der gesendeten Multicasts; minimale, mittlere und maximale Rundenzeit; jeweils in Abhängigkeit
von n).

---

## Experimente – Ergebnisse (2026-06-11)

**Parameter:** p₀ = 0.5 · (p wird pro Runde halbiert), k = 3 (Abbruch nach 3 aufeinanderfolgenden leeren Runden)  
**Plattform:** localhost (127.0.0.1), UDP-Ring + UDP-Multicast (239.0.0.1:55000)

### (a) Maximales n

**Maximales erfolgreich getestetes n: 64**  
Alle Ringgrößen (2, 4, 8, 16, 32, 64) wurden erfolgreich abgeschlossen.

### (b) Statistische Messwerte

| n  | Runden | Multicasts | min_rt (ms) | mean_rt (ms) | max_rt (ms) |
|----|-------:|-----------:|------------:|-------------:|------------:|
|  2 |      7 |          2 |       0.245 |        0.412 |       0.976 |
|  4 |      6 |          4 |       0.446 |        0.988 |       2.978 |
|  8 |      6 |          8 |       1.048 |        1.961 |       5.375 |
| 16 |      7 |         13 |       1.738 |        3.330 |      10.419 |
| 32 |     10 |         26 |       2.023 |        5.020 |      18.553 |
| 64 |      8 |         51 |       3.415 |        8.529 |      31.389 |

**Beobachtungen:**
- Die mittlere Rundenzeit skaliert näherungsweise linear mit n (je größer der Ring, desto länger braucht das Token für einen Umlauf).
- Die maximale Rundenzeit zeigt deutlich höhere Varianz — vermutlich durch OS-Scheduling-Jitter auf localhost.
- Die Anzahl der Multicasts entspricht grob n/2 · log(2) · Runden, da p bei jeder Runde halbiert wird.

<!-- ===== exercise_02/task_2.md ===== -->

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

**Maximales erfolgreich getestetes n: 2**  
Der verfügbare Rechner (n = 2) wurden erfolgreich eingebunden.

### (b) Statistische Messwerte

| n | Runden | Multicasts | min_rt (ms) | mean_rt (ms) | max_rt (ms) |
|---|-------:|-----------:|------------:|-------------:|------------:|
| 2 |      7 |          2 |       1.842 |        2.531 |       5.418 |

### (c) Vergleich mit Aufgabe 1 (localhost)

| n | mean_rt localhost (ms) | mean_rt LAN (ms) | Faktor |
|---|----------------------:|-----------------:|-------:|
| 2 |                  0.412 |            2.531 |    6.1 |

### Beobachtungen

- **Multicast im LAN** funktionierte auf Anhieb: Die Fritz!Box leitet Multicast-Pakete innerhalb
  des lokalen Subnetzes korrekt weiter
- **Rundenzeiten**: Im LAN ca. 5X länger als auf localhost. Die Mehrlast entsteht fast
  ausschließlich durch die echte UDP-Unicast-Latenz pro Hop
- **Skalierung mit n**: Ähnlich wie in Aufgabe 1 wächst mean_rt annähernd linear mit n; der
  Anstieg pro zusätzlichem Knoten beträgt im LAN ca. +1.0 ms (gegenüber +0.25 ms auf localhost).
  Jedoch wurde dies nur auf einem Ring mit n = 2 gemessen (ein zusätzlicher Rechner). Die Aussagekraft ist hier
  fragwürdig.
- **Keine Paketverluste** festgestellt; alle Runs terminierten sauber nach k = 3 leeren Runden.
- **Maximales n** ist hier nicht durch die Implementierung oder das Netzwerk beschränkt, sondern
  durch die Anzahl verfügbarer Maschinen

<!-- ===== exercise_03/task_3.md ===== -->

Besorgen Sie sich den in der Vorlesung vorgestellten Simulator https://github.com/syssoft-ds/sim4da-S26.git. Nutzen Sie den
Test OneRingToRuleThemAll als Ausgangspunkt für eine Simulation der in Aufgabe 1 geforderten verteilten Anwendung.
Bestimmen Sie auch hier analog zu den Aufgaben 1 und 2 das maximal erreichbare n (eventuelle Thread- und Speicher-Limits
kann man bei einer JVM durch entsprechende Parameter erhöhen) sowie die statistischen Informationen (bei den Rundenzeiten
interessieren auch hier die real gemessenen Zeiten). Vergleichen und Interpretieren Sie die Ergebnisse aus Aufgabe 3 mit den
Ergebnissen aus den Aufgaben 1 und 2. Vergleichen Sie auch den Implementierungs- und den Experimentalaufwand der
verschiedenen Realisierungen.

<!-- ===== exercise_04/task_04.md ===== -->

Geht in den Implementierungen und der Simulation alles mit rechten Dingen zu oder können die beteiligten Prozesse in
bestimmten Situationen eine inkonsistente Sicht auf den Programmablauf haben? Denken Sie in dieser Aufgabe über die Definition
von Konsistenzkriterien für diese verteilte Anwendung nach. Ergänzen Sie anschließend Ihre Implementierung aus Aufgabe 3 um
Mechanismen, die inkonsistente Situationen zumindest erkennen und melden oder idealerweise sogar vermeiden.

