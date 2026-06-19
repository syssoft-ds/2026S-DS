# Aufgabe 2 – Ein Feuerwerk an UDP-Nachrichten (Verteilt)

Dieselbe Anwendung wie Aufgabe 1 (logischer Ring, Token kreist per **Unicast-UDP**, wer das Token
hält zündet mit Wahrscheinlichkeit `p` eine Rakete als **Broadcast** und halbiert `p`; Terminierung
nach `k` stillen Runden) – jetzt aber auf **mehreren realen Rechnern**, **ein Prozess pro Rechner**.

Hier konkret: **PC = Knoten 0** (Initiator, misst & terminiert), **Android-Handy = Knoten 1**
(läuft über **Termux + OpenJDK 17**, führt dieselbe `.jar` aus). Damit ist das realistische
**n = 2**; mehr Geräte = einfach mehr Zeilen in `members.txt`.

> Literatur: Ring-/Token-Koordination *Coulouris §15.2*; IP-Multicast & Gruppenkommunikation inkl.
> Unicast-Emulation *Coulouris §4.4.1 & §15.4 / Van Steen §4.4*; UDP-Fehlermodell *Coulouris §4.2.1 /
> Van Steen §4.2*; reale Netz-Latenz vs. lokal *Coulouris §2.4*.

---

## Was sich gegenüber Aufgabe 1 geändert hat

Aufgabe 1 adressierte implizit `127.0.0.1:basePort+rank`. Aufgabe 2 nutzt eine explizite
**Membership-Tabelle** (rank → `host:port`), gesteuert über **Umgebungsvariablen** – die positionale
Argumentliste bleibt identisch, der Knoten läuft also weiterhin auch lokal (zwei JVMs) zum Testen.

| Variable        | Bedeutung                                                                 | Default                          |
|-----------------|---------------------------------------------------------------------------|----------------------------------|
| `RING_MEMBERS`  | Pfad zu `members.txt` (`rank host port`) **oder** inline `r=host:port;…`   | leer ⇒ lokaler Aufgabe-1-Modus   |
| `RING_BCAST`    | `unicast` (n Unicasts) **oder** `multicast`                                | verteilt: `unicast`, lokal: `multicast` |
| `RING_BIND`     | Bind-Adresse des Unicast-Sockets                                           | verteilt: `0.0.0.0`, lokal: `127.0.0.1` |
| `RING_MCAST_IF` | Multicast-Interface (Name/IP), nur bei `RING_BCAST=multicast`              | automatisch (verteilt: reales IF)|

**Broadcast-Abbildung:** Die Aufgabe erlaubt, Broadcasts *möglichst* auf Multicast abzubilden, sonst
auf `n−1` Unicasts. Auf Android verwirft die WLAN-Schicht eingehende Multicasts i. d. R. mangels
`WifiManager.MulticastLock` (den eine reine Termux-JVM nicht hält). Deshalb ist im verteilten Modus
**`unicast` der Default**: ein Feuerwerk wird als je ein Unicast-Datagramm an **jeden** Knoten
(inkl. sich selbst, für Loopback-Parität) auf Port `mcPort+rank` gesendet. `RING_BCAST=multicast`
kann man im LAN versuchen (siehe unten).

Eindeutig in der Statistik: die `SUMMARY`-Zeile enthält jetzt `bcast=<modus>`.

---

## 1. Build (PC)

```powershell
cd "Aufgabe 2\udp-fireworks-distributed"
.\gradlew.bat jar
# -> build\libs\udp-fireworks-distributed.jar
```

Die JAR enthält eine `Main-Class`, läuft also als `java -jar udp-fireworks-distributed.jar <args>`
**oder** `java -cp udp-fireworks-distributed.jar firework.RingNode <args>`.
Bytecode ist portabel – dieselbe JAR läuft auf dem Handy (Termux/OpenJDK 17).

## 2. Handy vorbereiten (Termux)

1. **Termux** aus **F-Droid** installieren (die Play-Store-Version ist veraltet).
2. In Termux:
   ```bash
   pkg update && pkg install openjdk-17
   java -version          # sollte 17 zeigen
   ```

## 3. JAR aufs Handy übertragen (gleiches WLAN, ohne Zusatztools)

PC im JAR-Ordner einen kurzen HTTP-Server starten …
```powershell
cd "Aufgabe 2\udp-fireworks-distributed\build\libs"
python -m http.server 8000
```
… und in Termux holen (PC-IP siehe Schritt 4):
```bash
curl -O http://<PC-IP>:8000/udp-fireworks-distributed.jar
```
*(Alternativen: `git clone` des Repos in Termux und dort `./gradlew jar`, oder per USB-Kabel kopieren.)*

## 4. Netzwerk (gleiches WLAN)

- **IPs ermitteln:** PC `ipconfig` (IPv4 des WLAN-Adapters, z. B. `192.168.1.50`);
  Handy in Termux `ip addr` bzw. `ifconfig` (z. B. `192.168.1.77`).
- **Erreichbarkeit testen:** vom Handy `ping <PC-IP>`, vom PC `ping <Handy-IP>`.
  Schlägt das fehl, blockt der Router **Client-Isolation** → auf **Handy-Hotspot** (PC verbindet sich
  mit dem Handy) oder **USB-Tethering** ausweichen (gleiche Befehle, nur andere IPs).
- **Windows-Firewall** (häufige Stolperfalle): eingehendes UDP zum Java-Prozess im *privaten* Netz
  erlauben – beim ersten Start dem Windows-Prompt zustimmen, oder einmalig (Admin-Terminal):
  ```powershell
  netsh advfirewall firewall add rule name="udp-fireworks" dir=in action=allow protocol=UDP localport=6000,4446,4447
  ```

## 5. Starten

### Variante A – mit dem Helfer-Skript (empfohlen)

`members.txt` erzeugen und die fertigen Startbefehle anzeigen lassen
(rank-Reihenfolge = `--hosts`, also PC zuerst):
```powershell
python scripts\run_distributed.py --hosts <PC-IP>:6000,<Handy-IP>:6000
```
Das schreibt `members.txt` und druckt je Knoten den Startbefehl (PowerShell fürs PC, bash/Termux
fürs Handy). `members.txt` muss auf **beiden** Geräten vorliegen (auf dem Handy ins JAR-Verzeichnis
legen – z. B. ebenfalls per `curl` vom HTTP-Server holen).

Dann **das Handy zuerst** starten (Termux, im JAR-Verzeichnis):
```bash
RING_MEMBERS=members.txt RING_BCAST=unicast java -jar udp-fireworks-distributed.jar 1 2 0 230.0.0.1 4446 0.5 3 8000 1 false 30000
```
… und den PC-Knoten 0 starten **und** die Statistik automatisch einsammeln:
```powershell
python scripts\run_distributed.py --launch-local --no-build
```
Knoten 0 wartet bis zu 30 s, bis alle `READY` da sind, injiziert dann das Token, terminiert nach
`k` stillen Runden und schreibt **`results_distributed.csv`**.

### Variante B – komplett manuell

`members.txt` von Hand anlegen (siehe `members.example.txt`), auf beide Geräte verteilen, und auf
jedem Gerät den passenden Befehl aus der Skript-Ausgabe ausführen. Knoten 0 (PC) gibt die
`SUMMARY`-Zeile aus.

> Argument-Reihenfolge: `rank n basePort mcAddr mcPort p0 k idleMs ttl verbose startupMs`.
> Im verteilten Modus ist `basePort` ohne Wirkung (die Adressen kommen aus `members.txt`) → `0`.

---

## 6. Ergebnisse & Statistik

Knoten 0 gibt z. B. aus:
```
SUMMARY n=2 status=ok rounds=7 multicasts=4 rt_min_ms=12.3 rt_mean_ms=18.7 rt_max_ms=41.2 p0=0.5 k=3 bcast=unicast
```

- **Token-Runden** = abgeschlossene Umläufe bis zur Terminierung.
- **Feuerwerke** (`multicasts`) = Anzahl gezündeter Raketen (Ereignisse). Bei `bcast=unicast`
  entspricht **jedes** Ereignis `n` Unicast-Datagrammen auf dem Draht; bei `bcast=multicast` einem
  Multicast. Im Bericht beide Lesarten nennen.
- **Rundenzeit min/mittel/max (ms):** jetzt **echte WLAN-RTT** statt µs auf localhost – der zentrale
  Unterschied zu Aufgabe 1 (eine Runde = `n` Netz-Hops über WLAN; Jitter & Latenz dominieren).
- **Maximales n:** = Anzahl erreichbarer realer Rechner. Hier **2** (PC + Handy). Mehr Geräte
  (weitere Handys/Laptops von Freund:innen) einfach als Zeilen in `members.txt` ergänzen; das Limit
  ist hier die Hardware-Verfügbarkeit, nicht die Implementierung.

**Vergleich zu Aufgabe 1 (für den Bericht):** lokal lagen die Rundenzeiten im µs–ms-Bereich und n
skalierte bis ~256; verteilt dominiert die Netzlatenz (ms je Hop), dafür ist jeder Knoten ein
echter, unabhängiger Rechner (keine geteilten JVM-/CPU-Ressourcen). Implementierungs- und
Experimentalaufwand: Aufgabe 2 braucht Adress-/Membership-Verwaltung, Firewall/Netz-Setup und
manuelles Starten je Gerät – deutlich höher als das Skript-gesteuerte Aufgabe 1.

---

## 7. Multicast im LAN versuchen (optional)

```powershell
# members.txt mit --bcast multicast erzeugen, Knoten mit RING_BCAST=multicast starten:
python scripts\run_distributed.py --hosts <PC-IP>:6000,<Handy-IP>:6000 --bcast multicast
```
Erwartung: PC↔PC-Multicast im LAN klappt oft, **Android empfängt aber meist keine Multicasts**
(fehlender `MulticastLock`). Genau dieser Befund rechtfertigt den Unicast-Fallback und ist ein
legitimes Experimentalergebnis für den Bericht. `RING_MCAST_IF=<IP|name>` wählt das Interface,
`--ttl 1` hält Pakete im lokalen Subnetz.

## 8. Troubleshooting

| Symptom                                   | Ursache / Lösung                                                        |
|-------------------------------------------|------------------------------------------------------------------------|
| `startup_failed` (READY fehlt)            | Knoten 0 erreicht andere nicht: Firewall? `ping` beidseitig? IP korrekt in `members.txt`? |
| `ping` schlägt fehl                       | Router-Client-Isolation → Handy-Hotspot oder USB-Tethering nutzen.     |
| Keine Feuerwerke beim Gegenüber sichtbar  | Multicast-Modus auf Android → `RING_BCAST=unicast` verwenden.          |
| `RING_MEMBERS ... n=… (muessen übereinstimmen)` | `n`-Argument ≠ Zeilenzahl in `members.txt`.                       |
| `status=stalled`                          | Token-Datagramm verloren (pure UDP-Semantik, kein Retransmit) → erneut starten. |

---

## Dateien

- `src/firework/RingNode.java` – Ring-Knoten (Membership + Multicast/Unicast-Broadcast).
- `scripts/run_distributed.py` – Config-Generator + Start-Helfer (`--hosts`, `--launch-local`).
- `members.example.txt` – Vorlage für `members.txt` (rechnerspezifisch, nicht eingecheckt).
- `build.gradle.kts`, `settings.gradle.kts`, Gradle-Wrapper.
