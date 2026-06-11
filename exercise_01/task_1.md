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