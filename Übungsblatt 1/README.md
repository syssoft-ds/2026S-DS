# Verteilte Systeme – Übungsblatt 1: Ein Feuerwerk an UDP-Nachrichten

Implementierung eines Token-Ring-Algorithmus (Le Lann 1977), in dem der
jeweilige Token-Besitzer mit Wahrscheinlichkeit `p` eine Rakete (Broadcast)
zündet und `p` danach halbiert. Die Anwendung terminiert nach `k` stillen
Runden. Realisiert pseudo-verteilt (Aufgabe 1), real verteilt (Aufgabe 2),
als Simulation (Aufgabe 3) und um Konsistenzmechanismen erweitert (Aufgabe 4).

## Struktur

### Aufgabe 1 & 2 – Python (UDP)
| Datei | Beschreibung |
|-------|--------------|
| `firework.py` | Ein Ring-Prozess (localhost). Token-Ring per UDP-Unicast, Raketen per Multicast, ACK/Retransmit für zuverlässige Token-Übergabe |
| `messwerte.py` | Automatisiert die Messreihe über wachsende `n` und schreibt `results.csv` |
| `plots.py` | Erzeugt die Diagramme aus der `results.csv` |
| `firework_dist.py` | Verteilte Variante (Aufgabe 2): Topologie aus `ring.json`, Broadcast wählbar (Multicast oder n−1 Unicasts) |
| `make_ring.py` | Erzeugt die `ring.json` aus den IP-Adressen der Teilnehmer |
| `auswerten.py` | Aggregiert die Statistik-Dateien der beteiligten Rechner |

### Aufgabe 3 & 4 – Java (sim4da, Gradle-Projekt)
| Datei | Beschreibung |
|-------|--------------|
| `src/fireworks/FireworksSimulation.java` | Simulation des Rings; ein JVM-Lauf je `n` |
| `messreihe.ps1` | Startet die Messreihe über wachsende `n` und sammelt `results_sim.csv` |
| `src/fireworks/FireworksConsistency.java` | Konsistenz-Erweiterung mit Lamport-Uhren (K3) und Token-Sequenznummer (K1); Stör-Modi Verlust und Umordnung.|

### Bericht
`Bericht_Übungsblatt_1.pdf` – Ergebnisbericht.

## Ausführung

### Aufgabe 1 (localhost)
```bash
python messwerte.py                 # volle Messreihe -> results/results.csv
python plots.py results/results.csv # Diagramme
```

### Aufgabe 2 (verteilt, alle Rechner im selben Netz)
```bash
python make_ring.py 192.168.x.a 192.168.x.b   # ring.json erzeugen, auf alle Rechner kopieren
# auf jedem Rechner ein Prozess, P0 zuletzt starten:
python firework_dist.py --config ring.json --id 1 --bcast unicast
python firework_dist.py --config ring.json --id 0 --bcast unicast
python auswerten.py stats           # Statistik der gesammelten node_*.json
```
Hinweis: Eingehender UDP-Port (Standard 50000) muss in der Firewall freigegeben
sein. WLAN-Router mit Client-Isolation verhindern die Kommunikation (ggf. ein
isolationsfreies Netz (Hotspot) verwenden).

### Aufgabe 3 (Simulation, Gradle)
Benötigt JDK 25 (der Gradle-Wrapper lädt das Toolchain-JDK bei Bedarf).
```bash
./gradlew run --args="8"            # Einzellauf n=8 (Windows: .\gradlew.bat)
./messreihe.ps1                     # Messreihe -> results_sim.csv
```

### Aufgabe 4 (Konsistenz)
`mainClass` in `build.gradle.kts` auf `fireworks.FireworksConsistency` setzen, dann:
```bash
# Argumente: n  p0  k  Verlust%  Umordnung%  Modus(detect|avoid)
./gradlew run --args="8 1.0 3 0 0 detect"     # ungestört -> keine Meldung
./gradlew run --args="8 1.0 3 0 40 detect"    # Umordnung -> K3 gemeldet
./gradlew run --args="8 1.0 3 0 40 avoid"     # K3 durch geordnete Auslieferung vermieden
./gradlew run --args="8 1.0 3 30 0 detect"    # Verlust -> K2 verletzt (Zustellrate < 100%)
```