# sim4da

**A Java framework for simulating distributed algorithms — built for teaching, scaled to be fun.**

You define your algorithm as an Actor that extends `Node` (or owns a
`NetworkConnection`), declare a few records implementing `Message`, and write
your `engage()` loop in modern pattern-matching style. That's the whole
framework.

## Quick start

sim4da ships as a single, zero-dependency JAR. There is nothing to install,
nothing to download from Maven Central, no transitive footprint to manage.

1. **Grab the JAR.** [`sim4da.jar`](sim4da.jar) lives at the root of this
   repository. It targets **Java 25** (GraalVM 25 LTS or any other JDK 25
   distribution).
2. **Drop it on your project's module path** — for example, place it in a
   `lib/` folder inside your own exercise project.
3. **Add it to your build.** With Gradle:
   ```kotlin
   dependencies {
       implementation(files("lib/sim4da.jar"))
   }
   java {
       toolchain { languageVersion = JavaLanguageVersion.of(25) }
   }
   ```
   With Maven, declare the JAR as a `system` dependency or install it into
   your local repository. From the command line:
   ```
   javac --module-path lib --add-modules org.oxoo2a.sim4da -d out MyDemo.java
   java  --module-path lib:out -m my.module/my.pkg.MyDemo
   ```
4. **Write your first simulation.** A walkthrough is in
   [FIRST_SIMULATION.md](FIRST_SIMULATION.md).

That is the entire onboarding. The rest of this README explains _what is
in_ the JAR.

## Core concepts

### Messages are records

Every message implements the `Message` marker interface. Records make
defining one a single line:

```java
record Token(int value)  implements Message {}
record EndMessage()      implements Message {}
```

Records are immutable, so the simulator can hand the same instance to every
recipient without defensive copying. (If you carry mutable containers —
lists, maps, arrays — reinforce immutability in a compact constructor; see
the `Message` Javadoc.)

### Actors implement `engage()`

The simplest Actor extends `Node` and overrides `engage()`:

```java
class RingSegment extends Node {
    private final String nextId;

    RingSegment(int id, int nextId) {
        super(String.valueOf(id));
        this.nextId = String.valueOf(nextId);
    }

    @Override
    protected void engage() {
        while (true) {
            ReceivedMessage rm = receive();
            if (rm == null) return;                // simulation has been shut down
            switch (rm.message()) {
                case Token(int v) -> send(new Token(v + 1), nextId);
                case EndMessage e -> { send(e, nextId); return; }
                default           -> throw new IllegalStateException(
                        "Unexpected message: " + rm.message());
            }
        }
    }
}
```

### The four verbs

Inside `engage()`, an Actor talks to the rest of the simulated system through
four operations:

| Verb                               | Meaning                                                                               |
| ---------------------------------- | ------------------------------------------------------------------------------------- |
| `send(message, toNodeName)`        | Non-throwing unicast. Drops silently if the recipient is unknown.                     |
| `sendChecked(message, toNodeName)` | Strict unicast. Throws `UnknownNodeException` for unknown recipients.                 |
| `broadcast(message)`               | Send to every other node.                                                             |
| `receive()`                        | Block until a message arrives, or return `null` if the simulation has been shut down. |
| `sleep(millis)`                    | Wait, interruptible by simulation shutdown.                                           |

The non-throwing variants are the default by design: code inside `engage()`
should read like the algorithm, not like a Java tutorial on checked
exceptions. Reach for `sendChecked` when the algorithm needs to react to a
missing recipient.

### IS-A vs. HAS-A

If extending `Node` doesn't fit your design, own a `NetworkConnection`
directly:

```java
class CustomActor {
    private final NetworkConnection nc = new NetworkConnection("Agent1");

    CustomActor() {
        nc.engage(this::run);
    }

    private void run() {
        ReceivedMessage rm = nc.receive();
        // ... algorithm ...
    }
}
```

The walkthrough mixes both: the `Coordinator` is HAS-A, the `RingSegment` is
IS-A.

### Lifecycle

```java
Simulator simulator = Simulator.getInstance();
// ... create your nodes ...
simulator.simulate();          // run until all nodes terminate
// or simulator.simulate(10);  // run with a 10-second timeout
simulator.shutdown();          // resets framework state for the next run
```

There is exactly one `Simulator` per program — singleton by design, because
"one program = one simulation" matches how one reasons about distributed
systems.

`Simulator.stop()` ends the simulation early — but only when called from
_outside_ (the test thread, an external scheduler), never from inside a node.
A real distributed system cannot be unilaterally stopped; termination must
propagate by messages or arrive from an external trigger. The framework
enforces this: a node thread that calls `stop()` gets an
`IllegalStateException` whose message names the legitimate alternatives.

## Logging

Every send, every receive, every broadcast, and any algorithm-level event
you record yourself appears as one line in `sim4da-<PID>.log` in the working
directory. Format: `[<source>,<seq>] <event>`, where `seq` is a
monotonically increasing per-source counter:

```
[Coordinator,2] sending to 0
[0,2] received from Coordinator
[0,3] sending to 1
[1,2] received from 0
```

Reading one node's events in increasing `seq` order gives that node's local
timeline. Cross-node order in the file is the JVM's commit order,
deliberately _not_ a global wall-clock time — distributed systems don't have
one, and pretending otherwise would teach the wrong lesson. Causality
between nodes has to be reconstructed from the messages themselves (or,
when `BellTower` lands, from logical clocks).

To record an event from inside `engage()`:

```java
log("round " + r + " complete");
```

The implementation lives in `internal.EventLog` — it does not pull in
SLF4J, Logback, or any other logging framework. sim4da's logging needs are
simple enough that a dependency-free implementation is the right fit, and
it lets the framework distribute as a single, standalone JAR.

To run a simulation without producing a log file at all (smoke tests,
performance experiments, anything where the file is just clutter):

```java
Simulator simulator = Simulator.getInstance();
simulator.disableLogging();    // call before creating nodes
// ... build the simulation, run, shut down ...
```

`disableLogging()` is reset by `shutdown()`, so a JUnit suite where one
test runs silently and the next runs loud just works.

## Modern Java in use

The framework leans on Java 21+ language features as a matter of design:

- **Records** for messages — immutability, deconstruction, no boilerplate.
- **Pattern-matching `switch`** with record patterns at the receive site.
- **Virtual threads** for nodes — a 10,000-node simulation costs heap, not
  reserved stack.
- **`ReentrantLock` + `Condition`** for the per-node mailbox, with proper
  interrupt propagation.
- **JPMS module** with `org.oxoo2a.sim4da` exported and
  `org.oxoo2a.sim4da.internal` deliberately hidden. Your code cannot
  import the simulation core — `import org.oxoo2a.sim4da.internal.Network;`
  is a compile error rather than a tempting shortcut.
- **Zero non-JDK dependencies.** The whole framework is one ~20 KB JAR;
  no transitive deps, no fat-JAR machinery, drop it on your module path
  and go.

## Building sim4da yourself

You do not need to do this to _use_ the framework — `sim4da.jar` at the
repo root is the only artifact you need. But if you are curious about the
internals, want to step through the framework in a debugger, or are
contributing back to the project:

```
./gradlew test       # run the test suite
./gradlew jar        # rebuild sim4da.jar into build/libs/
```

The Gradle wrapper checked in here pins the build to Gradle 9.x; the
project itself has no non-JDK runtime dependencies (JUnit 5 is test-scope
only).

## Further reading

- [FIRST_SIMULATION.md](FIRST_SIMULATION.md) — a guided walkthrough of the
  token-ring example, line by line. Recommended next step.
- Javadoc on `Message`, `Node`, `NetworkConnection`, `Simulator`.
- [docs/ROADMAP.md](docs/ROADMAP.md) — what is being designed next:
  `Topology`, `BellTower` (logical clocks), per-node `HostClock`.
- [docs/VERDICT.md](docs/VERDICT.md) — the code review that drove the
  current shape of the framework. Optional reading; useful if you want
  to see what changed and why.
