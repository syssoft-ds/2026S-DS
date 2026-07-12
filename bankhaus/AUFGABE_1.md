# Aufgabe 1 — Eine kleine verteilte Anwendung (das „Bankhaus")

## Was gefordert war

Ein verteiltes Kontensystem aus `n` vollständig vernetzten Prozessen in sim4da. Jeder Prozess
verwaltet einen ganzzahligen Saldo, alle starten mit demselben Betrag (1000), die Gesamtsumme
`S = n · 1000` ist damit von Anfang an bekannt. Wiederholt überweist ein Prozess nach kurzer
zufälliger Wartezeit einen zufälligen Betrag `0 < b ≤ balance` an einen zufällig gewählten
anderen Prozess: Er belastet sein Konto sofort und schickt `Transfer(b)` los; der Empfänger
schreibt `b` gut, wenn die Nachricht ankommt.

Zusätzlich verlangt das Übungsblatt zwei Verschärfungen des Simulator-Standardverhaltens:
**Nachrichtenlatenz** und **abgeschaltete FIFO-Zustellung**.

Die zu prüfende Invariante: *Summe aller Kontostände + Summe aller unterwegs befindlichen
Überweisungen = S.*

## Was ich gebaut habe

| Datei | Inhalt |
|---|---|
| `src/bankhaus/Messages.java` | Alle Nachrichtentypen als `record` mit Marker-Interface `Message` |
| `src/bankhaus/AccountNode.java` | Der Kontoprozess `P_i` (`extends Node`, IS-A-Muster) |
| `src/bankhaus/DelayedLinks.java` | Modelliert die Übertragungszeit der Kanäle |
| `src/bankhaus/Config.java` | Alle Parameter eines Laufs an einer Stelle |
| `src/bankhaus/BankhausRun.java` | Baut die Simulation auf und lässt sie laufen |
| `src/bankhaus/BankhausSimulation.java` | `main` für Aufgabe 1 + 2 |

Die Schnappschuss-Anteile (`SnapshotCoordinator`, die Kontrollnachrichten, das Einfärben in
`AccountNode`) gehören zu Aufgabe 2 und sind dort beschrieben. Aufgabe 1 ist bewusst die
minimale Anwendung darunter: `Transfer` ist die einzige Nachricht, die Geld transportiert, und
damit die einzige, die später den Kanalzustand bildet.

### Die Basisnachricht

```java
public record Transfer(long id, int amount, int senderColor) implements Message {}
```

`amount` ist der überwiesene Betrag. `id` macht eine konkrete Überweisung wiedererkennbar, damit
der Schnappschuss später nicht nur die Summe, sondern die einzelnen Nachrichten im Kanal ausgeben
kann. `senderColor` ist die Einfärbung für Aufgabe 2 — für die reine Anwendung ohne Bedeutung.

### Der Überweisungsvorgang

```java
private void doTransfer() {
    if (balance <= 0) return;
    String receiver = peers.get(rnd.nextInt(peers.size()));
    int amount = 1 + rnd.nextInt(Math.min(balance, cfg.maxTransfer()));

    balance -= amount;                       // Sendeereignis: sofort belasten
    sentCount.merge(receiver, 1, Integer::sum);
    transmit(new Transfer(nextId(), amount, color), receiver);
}
```

`amount = 1 + rnd.nextInt(min(balance, maxTransfer))` erfüllt genau `0 < b ≤ balance`. Die
Deckelung durch `maxTransfer` (250) ist keine Anforderung, sorgt aber dafür, dass sich das Geld
nicht sofort bei einem einzigen Prozess sammelt und danach fast niemand mehr überweisen kann —
mit leeren Konten wären die Kanäle leer und der Schnappschuss langweilig.

Der Empfang ist die Gegenbuchung:

```java
receivedCount.merge(sender, 1, Integer::sum);
balance += t.amount();
```

Zwischen `balance -= amount` beim Sender und `balance += amount` beim Empfänger existiert das
Geld nirgends in einem Konto — es ist *im Kanal*. Genau dieses Fenster macht die Anwendung
inkonsistenzträchtig, und genau darum vergrößern wir es künstlich.

### Warum kein Hilfsthread, sondern eine Selbstnachricht

Ein Prozess muss zwei Dinge gleichzeitig tun: periodisch überweisen und Nachrichten empfangen.
`receive()` blockiert aber. Der naheliegende Weg wäre ein zweiter Thread, der in einer Schleife
schläft und überweist — dann würden aber zwei Threads gleichzeitig `balance` und die Zähler
anfassen, und das „Sichern des lokalen Zustands" in Aufgabe 2 müsste gegen laufende Überweisungen
gesperrt werden.

Stattdessen schickt sich der Knoten den Taktgeber selbst zu:

```java
private void scheduleTick() {
    int delay = cfg.minTickMs() + rnd.nextInt(cfg.maxTickMs() - cfg.minTickMs() + 1);
    links.after(delay, () -> send(new Tick(), nodeName()));   // Selbstnachricht
}
```

`Tick` durchläuft dieselbe Mailbox wie jede andere Nachricht. Die `engage()`-Schleife bleibt der
einzige Ort, an dem lokaler Zustand verändert wird:

```java
while (true) {
    ReceivedMessage rm = receive();
    if (rm == null) return;                     // Simulation beendet
    switch (rm.message()) {
        case Tick ignored           -> onTick();                 // überweisen, neuen Tick planen
        case Transfer t             -> onTransfer(rm.sender(), t);
        case SnapshotRequest(int c) -> takeSnapshotsUpTo(c);      // Aufgabe 2
        ...
    }
}
```

Damit ist der lokale Zustand ohne jede Sperre konsistent, und „Zustand sichern" ist automatisch
atomar gegenüber Senden und Empfangen — ein Prozess kann seinen Schnappschuss niemals mitten in
einer Überweisung aufnehmen. Das ist keine Bequemlichkeit, sondern Voraussetzung dafür, dass der
gemeldete Saldo überhaupt einem Ereigniszeitpunkt entspricht.

### Nachrichtenlatenz

sim4da legt eine Nachricht sofort in die Mailbox des Empfängers. Der naive Weg, das zu
verzögern — `sleep(latenz); send(...)` — wäre falsch: dann steht der *Prozess* still, nicht die
*Nachricht*. Das Konto wäre erst nach der Wartezeit belastet, und im Kanal wäre nie etwas
unterwegs.

Deshalb `DelayedLinks`: der Knoten belastet sein Konto sofort und übergibt den eigentlichen
Sendevorgang als `Runnable` an einen Scheduler, der ihn nach einer zufällig gezogenen Verzögerung
(40–200 ms) ausführt.

```java
public void after(int delayMs, Runnable delivery) {
    exec.schedule(delivery, delayMs, TimeUnit.MILLISECONDS);
}
```

Der Scheduler läuft auf eigenen Daemon-Threads, nicht auf den Knoten-Threads des Simulators —
der Prozess arbeitet währenddessen weiter. Zwischen Sende- und Zustellereignis vergeht echte Zeit,
und weil jede Nachricht ihre eigene Verzögerung zieht, **überholen sich Nachrichten desselben
Kanals**. Bei einem Überweisungstakt von 30–90 ms und einer mittleren Latenz von 120 ms sind pro
Prozess ständig ein bis zwei Überweisungen unterwegs; gemessen sind es bei `n = 5` im Mittel
10 Überweisungen im gesamten System.

### FIFO-Zustellung abschalten

Zusätzlich zur Überholmöglichkeit auf dem Kanal wählt der Empfänger eine *zufällige* Nachricht aus
seiner Warteschlange statt der ältesten (`BankhausRun`):

```java
SimulationBehavior.setMessageQueueSelectionDistributionFunction(
        RandomValues.getUniformDistribution());
```

Die Kanäle sind damit konsequent nicht-FIFO — aus zwei unabhängigen Gründen. Für Aufgabe 2 ist das
der entscheidende Punkt: das klassische Chandy-Lamport-Verfahren setzt FIFO voraus, das
Einfärbeverfahren nicht (siehe [AUFGABE_2.md](AUFGABE_2.md)).

### Laufzeitbegrenzung

Die Anwendung läuft nicht „eine feste Anzahl Überweisungen", sondern bis der Koordinator sie
beendet: nach der letzten Schnappschuss-Runde schickt er `StopTransfers` (keine neuen
Überweisungen mehr), wartet `4 · maxLatenz + maxTick` ms, damit die Kanäle leerlaufen, und schickt
dann `Terminate`. Jeder Knoten verlässt daraufhin `engage()`, und `simulator.simulate()` kehrt
zurück. Ein Wachhund-Thread außerhalb der Simulation ruft `Simulator.stop()`, falls ein Lauf sein
Zeitlimit überschreitet — aus einem Knoten heraus wäre das nicht erlaubt und wäre auch
konzeptionell falsch: ein verteiltes System kann nicht von einem einzelnen Prozess angehalten
werden.

## Wie man es laufen lässt

```bash
cd bankhaus
./gradlew run                # n = 5
./gradlew run --args="8"     # n = 8
```

Oder ohne Gradle:

```bash
javac -d out -cp lib/sim4da.jar src/bankhaus/*.java
java -cp "out;lib/sim4da.jar" bankhaus.BankhausSimulation 5
```

## Inwiefern das die Aufgabe löst

* **n vollständig vernetzte Prozesse.** `AccountNode` kennt alle anderen Konten namentlich und
  kann jedem direkt senden; sim4da hat ohnehin eine vollständige Topologie.
* **Konto mit ganzzahligem Saldo, gleicher Startbetrag, bekanntes S.** `Config.initialBalance = 1000`,
  `Config.totalMoney() = n · 1000`.
* **Wiederholte Überweisung eines zufälligen Betrags an einen zufälligen anderen Prozess nach
  kurzer zufälliger Wartezeit.** `Tick` → `doTransfer()`, `0 < b ≤ balance` per Konstruktion.
* **Sofortige Belastung beim Sender, Gutschrift beim Empfang.** Genau die zwei Zeilen oben.
* **Latenz und Nicht-FIFO.** `DelayedLinks` und die Verteilungsfunktion für die Warteschlange.
* **Läuft lange genug.** Der Koordinator löst mehrere Schnappschüsse während des laufenden
  Betriebs aus; die Überweisungen gehen währenddessen ununterbrochen weiter.

Die Invariante selbst wird hier noch nicht geprüft — dafür braucht es den Kanalzustand, also
Aufgabe 2. Dass sie gilt, zeigt Aufgabe 3.
