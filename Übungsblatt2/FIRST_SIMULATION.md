# My first simulation of a distributed algorithm

This walkthrough takes a complete sim4da example —
[`OneRingToRuleThemAllTest`](test/org/oxoo2a/test/OneRingToRuleThemAllTest.java),
the canonical "Hello, world!" of the framework — and unpacks it line by
line. By the end you'll have read a token-ring simulation top to bottom:
both the **IS-A** and **HAS-A** node patterns, record-based messages,
pattern-matching receive, and termination by propagating an `EndMessage`
through the ring.

You can either follow along by reading the file linked above, or paste
the snippets below into a fresh project that uses `sim4da.jar` on its
module path (see [README.md](README.md#quick-start) for the one-time
setup). The framework code is identical either way.

## The problem

A classic distributed-systems exercise:

- Arrange `n` nodes in a logical ring.
- One of them injects a *token*; each node increments and forwards it.
- Eventually a *termination signal* propagates around the ring once and
  shuts every node down.

We'll model:

- **Five `RingSegment` nodes**, named `"0"` through `"4"`, each forwarding
  to the next in a cycle.
- **One `Coordinator` node**, separate from the ring, that injects the
  initial token, waits five seconds, and then injects an `EndMessage`.

## The messages

Every message in sim4da is a record implementing the `Message` marker
interface:

```java
record Token(int value)  implements Message {}
record EndMessage()      implements Message {}
```

That's it — no copy constructors, no `extends`, no boilerplate. Records are
**immutable**, so when a sender calls `send(...)` the simulator hands the
same instance to every recipient without defensive copying. If a record
carried a mutable container — say `record Snapshot(List<Integer> values)` —
the contract would need to be reinforced with `List.copyOf(...)` in a
compact constructor; see the `Message` Javadoc for that nuance.

## The Coordinator (HAS-A pattern)

The Coordinator does **not** extend `Node`. It owns a `NetworkConnection`
and engages it with a runnable. This is the **HAS-A pattern** — useful
when the actor's class hierarchy is dictated by something else, or when
you simply prefer composition.

```java
static class Coordinator {
    private final NetworkConnection nc = new NetworkConnection("Coordinator");
    private final int waitMillis;

    Coordinator(int waitMillis) {
        this.waitMillis = waitMillis;
        nc.engage(this::run);
    }

    private void run() {
        nc.send(new Token(0), "0");
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        nc.send(new EndMessage(), "0");
    }
}
```

Worth noticing:

- `new NetworkConnection("Coordinator")` registers this node with the
  framework's `Network` under the name `"Coordinator"`. From now on, any
  other node can address it by that name.
- `nc.engage(this::run)` schedules `run` to start on a virtual thread when
  `Simulator.simulate()` is called. The thread is created and parked
  immediately; it does not begin executing until the simulation starts.
- Inside `run`, the Coordinator first sends a `Token(0)` to node `"0"`,
  then sleeps for `waitMillis` ms, then sends an `EndMessage` to `"0"`.
  Both calls go through `nc.send(...)`, the **non-throwing** variant — if
  no node `"0"` were registered, the send would simply be dropped. (Use
  `nc.sendChecked(...)` to surface a missing recipient as an exception.)
- `Thread.sleep` declares `InterruptedException`. The Coordinator restores
  the interrupt flag and returns — the canonical "I've been told to shut
  down" pattern.

## The RingSegment (IS-A pattern)

Ring nodes are the algorithm proper. They extend `Node` directly. This is
the **IS-A pattern** — the most common shape for a sim4da actor.

```java
static class RingSegment extends Node {
    private final String nextId;

    RingSegment(int id, int nextId) {
        super(String.valueOf(id));
        this.nextId = String.valueOf(nextId);
    }

    @Override
    protected void engage() {
        while (true) {
            ReceivedMessage received = receive();
            if (received == null) return;          // simulation has been shut down
            switch (received.message()) {
                case Token(int v) -> {
                    System.out.printf("Ring segment %s received token %d from %s%n",
                                      nodeName(), v, received.sender());
                    sleep(500);
                    send(new Token(v + 1), nextId);
                }
                case EndMessage e -> {
                    System.out.printf("Ring segment %s terminating.%n", nodeName());
                    send(e, nextId);
                    return;
                }
                default -> throw new IllegalStateException(
                        "Unexpected message: " + received.message());
            }
        }
    }
}
```

Reading from the top:

- The constructor passes the node's identity (`String.valueOf(id)`) up to
  `Node`. From the framework's point of view, that string *is* the node's
  address. It also stores the name of its successor in the ring.
- `engage()` is the algorithm. Each iteration calls `receive()`, which
  **blocks** until a message arrives.
- If `receive()` returns `null`, the simulation has been shut down (the
  framework interrupted us; or some external thread called
  `Simulator.stop()`). We return immediately.
- Otherwise the **pattern-matching switch** dispatches on the message
  type:

  - `case Token(int v)` — a `Token` record, with its `value` component
    deconstructed straight into the local variable `v`. We print, sleep
    500 ms to simulate work, then *construct a new* `Token(v + 1)` and
    forward it. Note that we do not mutate the token — records are
    immutable; forwarding is "send a new one with the next value."
  - `case EndMessage e` — termination signal. We forward it (so it keeps
    circulating) and return from `engage()` ourselves. Once every segment
    has done this, the ring is empty and the simulation ends.
  - `default` is the safety net. `Message` is a non-sealed marker
    interface (so course assignments can introduce new message types), so
    the compiler cannot prove exhaustiveness; `default` makes the
    intent explicit.

## Wiring it up

```java
@Test
void testOneRingToRuleThemAll() {
    final int ringSize = 5;
    Simulator simulator = Simulator.getInstance();

    for (int i = 0; i < ringSize; i++) {
        new RingSegment(i, (i + 1) % ringSize);
    }
    new Coordinator(5000);

    simulator.simulate();
    simulator.shutdown();
}
```

Six lines of meaningful work:

1. Acquire the singleton simulator.
2. Construct five `RingSegment`s. Each constructor registers a
   `NetworkConnection` and parks a virtual thread on
   `awaitSimulationStart()`. The Java references are immediately discarded
   — the framework holds everything it needs internally via the
   `Network` registry.
3. Construct the `Coordinator` — same shape, on its own virtual thread.
4. `simulator.simulate()` fires the start latch; all six virtual threads
   unblock and run their `engage()` / `run()` methods.
5. `simulator.simulate()` returns once every node has finished `engage()`.
6. `simulator.shutdown()` resets framework state so the next test can
   start with a clean slate.

## Running it

Inside this repository:

```
./gradlew test --tests OneRingToRuleThemAllTest
```

In your own project that depends on `sim4da.jar`: drop the snippets above
into a class, expose the `@Test` method to JUnit 5 (or wrap the body in a
plain `main`), and run it through your build tool of choice. The
framework code does not care which.

Expected console output either way (the message order is deterministic;
the timing comes from the half-second sleeps in the segments):

```
sim4da Summer 2026
Ring segment 0 received token 0 from Coordinator
Ring segment 1 received token 1 from 0
Ring segment 2 received token 2 from 1
Ring segment 3 received token 3 from 2
Ring segment 4 received token 4 from 3
Ring segment 0 received token 5 from 4
Ring segment 1 received token 6 from 0
Ring segment 2 received token 7 from 1
Ring segment 3 received token 8 from 2
Ring segment 4 received token 9 from 3
Ring segment 0 terminating.
Ring segment 1 terminating.
Ring segment 2 terminating.
Ring segment 3 terminating.
Ring segment 4 terminating.
```

Reading the trace:

- The token enters at segment `0` (sent by the Coordinator at *t* = 0),
  then travels around the ring. Each hop costs 500 ms of simulated work,
  so a full rotation is 5 × 500 ms = 2.5 s.
- The Coordinator's 5-second wait permits roughly two full rotations —
  Token values 0 through 9.
- At *t* = 5 s, the Coordinator injects `EndMessage`. Segment 0 receives
  it, forwards it, exits. Segment 1 likewise. Each segment terminates as
  the marker reaches it.

## What you've learned

- **Messages are records.** A new message type costs you one line. The
  immutability is what makes "send by reference" safe.
- **The receive loop is a pattern-matching switch.** Each `case`
  deconstructs the message into the locals you need; the `null` check at
  the top is the framework's idiom for "simulation has been shut down."
- **The framework has two construction patterns.** IS-A (`extends Node`)
  for the common case; HAS-A (own a `NetworkConnection`) when composition
  fits better.
- **Termination is by message.** The Coordinator injects `EndMessage`;
  ring segments forward and exit; `simulate()` returns when every
  `engage()` has returned. This is the very pattern you'd build into a
  real distributed system. Compare with `Simulator.stop()`, which is only
  callable from outside the simulation — *a single node cannot stop the
  world.*
- **No try/catch ceremony in `engage()`.** Exception handling exists where
  it must (interrupt restoration, the explicit `*Checked` family) and is
  hidden where it can be.

## Where to go next

- Read the Javadoc on `Message`, `Node`, `NetworkConnection`, and
  `Simulator`.
- Modify the Coordinator to run *two* token rounds before injecting
  `EndMessage`, with a short pause in between.
- Implement a leader-election variant: each segment carries a unique
  random id; the highest id wins. Hint: introduce a
  `record Probe(int id) implements Message {}` and adjust the switch.
- Try the (currently empty) `Topology` and `BellTower` extension points —
  the first for constraining sends to declared neighbors, the second for
  a logical-clock layer (Lamport timestamps, vector clocks).
