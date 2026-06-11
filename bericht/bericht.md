# Bericht Übungsblatt 1 - Verteilte Systeme 2026

## Aufgabe 1
Für die Implementierung von Aufgabe 1 habe ich mir zuerst die Grobe funktionsweise von 
UDP durch Codex erklären lassen, da ich vorher noch keine Berührungspunkte mit 
Übertragungsprotokollen hatte. Für mich war es wichtig, erst einmal zu verstehen,
wie eine Verbindung zwischen 2 Rechnern aufgebaut wird und wie Nachrichten verschickt werden.

Ich habe mir dann mit Hilfe von Codex eine kleine Klasse UdpEndpoint.java erstellt, die
bei initialisierung einen Socket auf dem gegebenen localport öffnet und Methoden für send
und receive implementiert. Die Pakete die hin und hergeschickt werden können sind einfache
Records bestehend aus einem String und der Adresse des Senders.

Auf dem UdpEndpoint baut die RingNode auf. Jeder Teilnehmer des Tokenrings wird durch eine
Klasse RingNode.java dargestellt. Die RingNode besteht im Wesentlichen aus einem Main Loop, der 
kontinuierlich auf ein neues Receive wartet. Bevor die receive Schleife startet initialisiert die
RingNode ihren UdpEndpoint sowie einen Multicast Listener, der auf gesendete Multicasts von 
anderen Nodes wartet. Wenn der Node der Boolean Flag "startsWithToken" übergeben wurde,
signalisiert das der Node, dass sie den Anfang macht.

Der Token der herumgereicht wird ist eine kleine Record Struktur die einige Metadaten und Node 
übergreifende Informationen speichern kann. Der Token hat somit vollständiges Wissen über die
Runde und das Netzwerk und kann als Terminierungssignal genutzt werden, indem die Anzahl stiller 
Runden in den Token reingeschrieben wird.

Nach initialisierung durchläuft jede Node denselben Prozess: 

Warte auf Token -> Wenn kein Stop signal erhalten -> Reiche Token weiter -> Wenn wieder bei der
Start Node angekommen -> Runde abschließen, Metriken loggen -> Prüfen ob k Runden still waren 
-> neue Runde starten oder terminieren

Wenn Stop signal erhalten -> Versende Token mit Stop signal an nächste Node

Da ich einige Probleme hatte die laufenden Java Prozesse vollständig zu terminieren und belegte 
Ports wieder freizugeben, habe ich eine kleine Besonderheit in den Ring implementiert. Das Stop
Signal wird normal von Node zu Node weitergereicht. Wenn aber die erste Node das Stopsignal erhält
feuert diese nochmal einen extra Multicast, der ein Stop signal erhält. Sobald eine Node dieses 
Signal erhält weiß sie, dass der Ring beendet wurde und die Node terminiert sauber und gibt ihren
Port frei. Das war besonders hilfreich als ich mehrere Experimente hintereinander laufen gelassen 
habe und immer wieder dieselbe Port Range recycelt habe. So sind die Ports immer pünktlich frei
gewesen und alle Java Prozesse waren zum Start des neuen Experiments terminiert.

Der Rest der RingNode Klasse enthält einige logging Funktionen zum Tracken der Statistiken. Das habe
ich mir vollständig mit Codex generieren lassen.

Ebenso mit Codex generiert ist das Python Skript, um das Tokenring Experiment mehrfach auszuführen.

Ich habe ein maximales n = 896 geschafft. Das habe ich mit 32 gig Ram gerade so geschafft. Dabei hatte 
jeder Java Prozess (also jede Node) einen zugewiesenen Heap in Höhe von 9 MB. Das macht in etwa 8 gig Ram
für alle Nodes zusammen. Entweder braucht die JVM so viel overhead, dass ich nur ein Viertel meines Rams
für die tatsächlichen Prozesse nutzen konnte oder ich habe einen Fehler im Aufrufen der Prozesse mit den
Heap zuweisungen. Ich habe extra darauf geachtet keine anderen Speicherintensiven Anwendungen laufen zu lassen. 
Alle n wurden jeweils 10-mal hintereinander mit zufälligen Seeds durchgeführt. 
Für alle Experimente wurde p = 0.5 und k = 3 gewählt

Auffällig war, dass die Multicasts im Schnitt in etwa der Anzahl der Nodes entsprechen. Erst ab 128 Nodes
habe ich die Laufzeit deutlich gespürt. 

Die Ergebnisse sind in der folgenden Tabelle zu sehen:

| n   | runs | roundsMin | roundsMax | roundsMean | avgMsPerRound | multicastsMin | multicastsMax | multicastsMean | durationSecondsMin | durationSecondsMax | durationSecondsMean | heapMbPerNode |
|-----|------|-----------|-----------|------------|---------------|---------------|---------------|----------------|--------------------|--------------------|---------------------|---------------|
| 2   | 10   | 3         | 7         | 5.3        | 6.1608        | 0             | 4             | 1.8            | 0.25               | 0.391              | 0.277               | 4096          |
| 4   | 10   | 4         | 8         | 6          | 10.9183       | 1             | 7             | 3.5            | 0.297              | 0.469              | 0.378               | 2048          |
| 8   | 10   | 5         | 9         | 6.5        | 20.4440       | 4             | 12            | 7.6            | 0.485              | 0.578              | 0.536               | 1024          |
| 16  | 10   | 7         | 10        | 8.3        | 33.5493       | 12            | 20            | 15.5           | 0.828              | 1.046              | 0.909               | 512           |
| 32  | 10   | 6         | 11        | 8.7        | 69.5166       | 28            | 38            | 34             | 1.562              | 1.813              | 1.672               | 256           |
| 64  | 10   | 6         | 13        | 10         | 131.9233      | 47            | 74            | 59.9           | 3.11               | 3.829              | 3.305               | 128           |
| 128 | 10   | 10        | 16        | 11.8       | 265.3232      | 111           | 147           | 127.2          | 6.61               | 7.906              | 7.261               | 64            |
| 256 | 10   | 9         | 15        | 11.5       | 758.1629      | 238           | 297           | 256.2          | 15.594             | 17.594             | 16.25               | 32            |
| 512 | 10   | 10        | 16        | 12.6       | 1853.0518     | 488           | 535           | 514            | 37.828             | 43.39              | 39.8                | 16            |
| 896 | 10   | 11        | 17        | 13.9       | 3925.0974     | 850           | 938           | 903.1          | 80.546             | 85.172             | 82.437              | 9             |

Die einzelnen Durchläufe hatten eine schnell steigende durchschnittliche Laufzeit. Der Plot zeigt das ganz gut bei
logarithmisch skalierter x-Achse.

![img.png](tokenring_avg_ms.png)

## Aufgabe 2

![img.png](sim4da_avg_ms.png)
## Aufgabe 3


## Aufgabe 4

