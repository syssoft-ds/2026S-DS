# sim4da — Review & Modernization Verdict

> **Historical context.** This is the code review that drove the Summer
> 2026 modernization of sim4da. Everything below was written *before*
> the work landed; the resulting commits are the answer to every "fix
> this" / "redesign that" recommendation here. Read it as a study in
> how an existing teaching framework was brought forward to Java 25 —
> *not* as a description of the current code. For the up-to-date API,
> start with [../README.md](../README.md).

A code review of `sim4da-S26` (branch `module`) focused on the stated goals:
maximize Java's modern features, achieve high software aesthetics, demonstrate
the structure of an event-driven simulator, and keep complex distributed
algorithms simple to express.

---

## What I like about sim4da

The core idea is genuinely elegant: a Node *is* a thread, the Network is an
in-process router, and the user only sees four verbs — `send`, `broadcast`,
`receive`, `engage()`. `Node.java` is 88 lines and a student can read it
end-to-end on the first day. The IS-A vs HAS-A split (`Node` subclass vs.
owning a `NetworkConnection`) is a pedagogically rich distinction. Hiding
`Network`, `NodeProxy`, `MessageInTransit` inside an `internal` package and not
exporting it via `module-info.java` is good taste.

`OneRingToRuleThemAllTest.java:127` already shows the idiom we should
celebrate:

```java
switch (received.message()) {
    case Token t      -> { ... }
    case EndMessage e -> { ... }
    default           -> throw new IllegalStateException(...);
}
```

That's modern Java at its best, and it should drive the design of everything
else.

---

## Two real bugs to fix today

### 1. `RandomValues.getDouble` ignores its own distribution function
`RandomValues.java:11`

```java
double v = distributionFunction.get();
if (v < 0 || v > 1) { ... System.exit(-1); }
return min_value + Math.random() * (max_value - min_value);   // ← uses Math.random(), not v
```

`v` is sampled, range-checked, then thrown away. So
`SimulationBehavior.setMessageQueueSelectionDistributionFunction(...)`
silently has no effect — the message-order randomization is always uniform,
regardless of what students configure. Fix: `min + v * (max - min)`.

### 2. Every send copies the message twice
`Network.java:57` + `MessageInTransit.java:14`

```java
// Network.send:
MessageInTransit mit = new MessageInTransit(message.copy(), sender.NodeName());
// MessageInTransit ctor:
this.message = message.copy();
```

Both sites copy. Since `Message.copy()` is reflective (`Message.java:22`),
this is also slow and fragile. Fix is one line, but see the next section for
the deeper redesign that makes copying unnecessary.

---

## The single highest-leverage redesign: `Message` as a sealed interface + records

This is the change that would *most* fulfill the "maximize Java features" +
"high software aesthetics" + "fun to work with" goals.

Today, defining a message costs the user:

```java
static class Token extends Message {
    public int value;
    public Token() { super(); this.value = 0; }
    private Token(Token original) { super(original); this.value = original.value; }   // mandatory ceremony
}
```

…and the framework pays for it with reflection (`Message.java:22-31`) plus
mandatory deep-copy on every hop, plus a runtime `RuntimeException` if the
user forgets the copy constructor.

Make `Message` immutable and the entire problem dissolves:

```java
// Message.java
public sealed interface Message permits Token, EndMessage, /* user types */ {}

// User code:
public record Token(int value) implements Message {}
public record EndMessage() implements Message {}
```

Wins, all at once:

- Records are immutable ⇒ no aliasing ⇒ `copy()` is gone,
  `MessageInTransit.message()` can simply share the reference.
- `switch (msg) { case Token t -> ... }` is now **exhaustively checked at
  compile time** — the `default → throw new IllegalStateException` in the
  ring test becomes unnecessary; the compiler enforces completeness.
- "How do I define a message?" becomes a one-liner.
- The fragile reflective copy constructor in `Message.java` disappears.

If the framework should keep `permits` open so course assignments can add
their own messages, leave `Message` as a non-sealed marker interface —
students still get the immutability win for free. Sealing is the right move
when *we* ship a fixed catalog (Token, EndMessage, ProbeMessage,
AckMessage, …) for a chapter.

Side benefit: the entire `MessageTest.java` test (which exists only to verify
the reflective copy) goes away.

---

## Modern-Java upgrades that match the goals

### Virtual threads — `NetworkConnection.java:44`

```java
thread = new Thread(this::node_main_base);
// becomes
thread = Thread.ofVirtual().name(node_name).start(this::node_main_base);
```

A simulation with 10,000 nodes becomes trivial. This is the single most "wow,
that's modern Java" change available, and it's two lines.

### `BlockingQueue` instead of `synchronized` + `wait`/`notify` — `NodeProxy.java:18-38`

The current mailbox is the textbook 1990s idiom. A `LinkedBlockingDeque`
gives you blocking `take()` and clean interruption out of the box. Wrap it
in a small `Mailbox` class — the *intent* of `NodeProxy` is "a mailbox that
may deliver out of order", and that intent deserves a name.

Note: keeping the random-index pick from
`SimulationBehavior.selectMessageInQueue` requires an explicit
`ReentrantLock` + `Condition` instead of a plain `BlockingQueue`, but that
still reads cleaner than `synchronized`/`wait`.

### Pattern matching on records is the API

Already used in `OneRingToRuleThemAllTest`, but not really demoed in the
README. Show it as the canonical receive pattern:

```java
while (running) {
    switch (receive().message()) {
        case Token(int v)    -> sendBlindly(new Token(v + 1), nextId);
        case EndMessage e    -> { sendBlindly(e, nextId); running = false; }
    }
}
```

The record deconstruction (`case Token(int v)`) is exactly the kind of
Java-21 candy that makes the framework feel modern.

### Scoped values / structured concurrency

Overkill for the public API, but if a node ever spawns helper tasks (e.g.
for snapshot algorithms), `StructuredTaskScope` is the right tool and a
great teaching topic.

---

## Architecture decisions worth reconsidering

### Singleton `Simulator` and `Network`
`Simulator.java:20`, `Network.java:23`

Two tests in the same JVM cannot run independently — `Network.nodes` is
never cleared, `startSignal` is a one-shot `CountDownLatch`. For a course
this matters: students who run `mvn test` will see one test poison the
next. Either:

- introduce `Simulation simulation = Simulation.create()` (instance-owned
  `Network`, registry, latch); or
- keep the singleton for lecture simplicity, but add `Simulator.reset()`
  and call it from `@BeforeEach`.

Bonus: an instance-based simulator lets students compare two algorithms
side by side in one `main()`, which is an obvious lecture demo.

### `Topology`, `CompleteTopology`, `BellTower` are empty placeholders
`Topology.java`, `CompleteTopology.java`, `BellTower.java`

Empty classes in a teaching codebase are louder than they look — they tell
the reader "there's a feature here that isn't" and erode the trust that
everything you see is meaningful. Either implement them or delete them.
The names hint at really good ideas — a `BellTower` that ticks for
synchronous rounds is a *beautiful* image for an event-driven simulator.

### Custom topologies are the natural next feature

Right now the network is a complete graph by construction (everyone can
`send` to everyone). For asynchronous algorithms with explicit channels
(rings, trees, meshes), a `Topology` that constrains `send` to declared
neighbors would let students implement Chang-Roberts, Echo, BFS-build,
etc. without faking it inside the algorithm.

### Termination is awkward

`Simulator.simulate()` (no args) blocks on `nc.join()` for every node — so
an algorithm terminates only when every `engage()` returns. There's no way
for a node to say "we're done, stop the simulation." A `Simulator.stop()`
callable from inside a node would make Dijkstra-Scholten / Chandy-Lamport
/ leader-election demos much more natural.

---

## Polish that hurts aesthetics today

- **Naming is mixed.** `NodeName()` (PascalCase method, looks like a
  constructor) at `Node.java:72` next to `node_main_base` (snake_case) at
  `NetworkConnection.java:48` next to camelCase elsewhere. Pick one — Java
  convention says `nodeName()`. The easiest aesthetics win in the whole
  codebase.
- **Five places swallow `InterruptedException` silently**: `Node.sleep`
  (`Node.java:84`), `NetworkConnection.join` (`:60`),
  `Simulator.awaitSimulationStart` (`:74`), `Simulator.simulate` (`:36`),
  `NodeProxy.receive` (`:30` — at least prints). At minimum:
  `Thread.currentThread().interrupt();`. Better: in a teaching framework,
  this is a *teachable* moment about why the interrupt convention exists —
  make it correct.
- **`System.exit(-1)` in library code** — `RandomValues.java:15`,
  `SimulationBehavior.java:15`. JUnit cannot trap this; a misconfigured
  test takes down the JVM and gives no failure message. Throw —
  `OverwriteDistributionFunctionException` already exists at
  `OverwriteDistributionFunctionException.java:3` for one of these cases
  but is never used.
- **`stash/` will not compile against the current `Message`** — those
  files reference an `add("token", 0)` / `query("token")` /
  `queryHeader("sender")` API that no longer exists. Either delete them
  (they're stash for a reason) or restore that API as a `MapMessage`
  record for ad-hoc demos — it's actually a nice teaching tool because
  students can experiment without defining classes.
- **No build file.** Only an IntelliJ `.iml` plus jars in `lib/` (which
  `.gitignore` *ignores*, so checkout-and-build is broken on a fresh
  clone). A 30-line `build.gradle.kts` targeting Java 21 would fix CI,
  fix VS Code users, and let you turn on preview features cleanly.
- **Dead state**: `NodeProxy.nc` is stored but never read;
  `MessageInTransit(MessageInTransit original)` is never called.
- **JavaDoc nit**: `NetworkConnection.receive` (`:67`) says it returns
  "the received Message as a ReceivedMessage record" — `Message` and
  `ReceivedMessage` are different types; reads confusingly.

---

## One-sentence summary

The bones are excellent — small surface area, clean module boundary, the
right pedagogical instincts — but the framework is currently 80% Java-21
in spirit and 20% Java-6 in execution; converting `Message` to a sealed
record hierarchy, swapping platform threads for virtual threads, replacing
the singleton with an instance, and either implementing or deleting the
placeholder classes would let the simulator *feel* the way it already
*looks* in the README.

---

## Proposed roadmap

A reasonable ordering, smallest-blast-radius first:

1. **Bug fixes** — `RandomValues.getDouble` arithmetic; remove the
   double-copy in `MessageInTransit`/`Network.send`.
2. **`Message` redesign** — sealed (or open) interface + records; delete
   `Message.copy()` and `MessageTest`; update
   `OneRingToRuleThemAllTest` to record-deconstruction patterns.
3. **Virtual threads** in `NetworkConnection.engage`.
4. **Mailbox abstraction** wrapping a `BlockingQueue`/`Condition`-based
   queue; clean `InterruptedException` handling end-to-end.
5. **Replace `System.exit` with thrown exceptions** in `RandomValues` and
   `SimulationBehavior`; actually use
   `OverwriteDistributionFunctionException`.
6. **Naming pass** — camelCase everything; `nodeName()` instead of
   `NodeName()`.
7. **Instance-based `Simulation`** (or at least `Simulator.reset()`) so
   tests are independent.
8. **Implement or remove** `Topology`, `CompleteTopology`, `BellTower`.
   Decide whether constrained-neighbor topologies belong in v1.
9. **`Simulator.stop()`** callable from inside a node, for natural
   termination-detection demos.
10. **Build file** (`build.gradle.kts`, Java 21+) and a clean `lib/`
    story; remove jars from the repo.
11. **README** rewrite once the API stabilizes — show the
    pattern-matching receive loop as the headline.
