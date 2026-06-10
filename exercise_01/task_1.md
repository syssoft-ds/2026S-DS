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