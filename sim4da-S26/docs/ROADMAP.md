# Roadmap

What is being designed next for sim4da. Two rules:

1. **Anything ambiguous lives here**, not in code comments or commit
   messages. The repo's source is for decisions that have been made;
   this file is for decisions still in motion.
2. **Open questions are framed as questions.** Once a question gets a
   verdict, the verdict becomes a one-liner and the work gets done — at
   which point the entry leaves this file.

Students do not need to wait for any of this to land: the framework as
shipped in `sim4da.jar` is complete and stable for the exercises that
target it. The roadmap is here so that the curious can see where things
are going.

---

## Designing next

### Topology / CompleteTopology

Constrain `send` to declared neighbors. Currently the network is an
implicit complete graph; making the topology a first-class concept
unlocks rings, trees, meshes, partitioned graphs, and adversarial
network layouts.

**Open design questions:**

- *API shape* — declare the topology once before `simulate()`, or allow
  it to evolve during the simulation (link failure, partition healing)?
  Factory style (`Topology.ring(nodes)`, `Topology.tree(...)`,
  `Topology.mesh(...)`), or a graph-builder DSL?
- *Where it lives* — per-node neighbor list on `NodeProxy` (matches the
  "per-node infrastructure handle" framing), or a central adjacency
  table in `Network`? Both?
- *send to non-neighbor* — silent drop (matches the no-exceptions
  principle), or a `sendChecked`-style throwing variant
  (`NotANeighborException`)?
- *broadcast under a topology* — does it respect topology
  (= "send to my neighbors") or stay all-to-all? If both are useful,
  how do we name them?
- `CompleteTopology` is the implicit default, confirmed by the absence
  of any topology declaration.

### BellTower — logical clocks

A per-node logical-clock layer: Lamport timestamps and/or vector
clocks. The "bell tower" rings each event with logical time. State
naturally lives on `NodeProxy` because each node has its own clock.

**Open design questions:**

- *Which first* — Lamport (one integer per node) or vector clocks
  (one integer per node, per node)? Both side-by-side as alternative
  `BellTower` flavors?
- *Stamping policy* — automatic (every `send` increments, every
  `receive` merges), or opt-in per-message? Automatic gives clean
  semantics but couples the clock to every algorithm; opt-in keeps
  algorithms that don't care from paying.
- *Exposure to user code* — `ReceivedMessage.timestamp()` accessor,
  a wrapper record (`Stamped<T extends Message>`), or metadata on
  `MessageInTransit` that the framework logs but the algorithm has
  to read explicitly?
- *Independence* — confirm BellTower works regardless of the chosen
  topology.

---

## Ideas / nice-to-haves

### Per-node HostClock — clock-skew simulation

A per-node fake physical clock with a random offset from "true time"
and (optionally time-varying) drift. Different in purpose from the
logical-clock `BellTower`:

- `BellTower` is about *causality* — teaching Lamport timestamps,
  vector clocks, happened-before reasoning.
- `HostClock` is about *clock synchronization* — teaching NTP,
  Berkeley algorithm, Christian's algorithm. Students write
  algorithms that have to reconcile divergent local clocks.

**Open design questions:**

- *Skew model* — constant offset (simplest); offset + linear drift;
  or time-varying offset/drift via a configurable function?
- *Where it lives* — `HostClock` per node on `NodeProxy` per the
  layering discipline, surfaced as `now()` on `NetworkConnection`
  and `Node`.
- *Whether the log line should include the host-clock reading* —
  lean towards "no". `[node,seq]` is for causality; clock readings
  are algorithm-level data students surface explicitly via
  `log("t=" + now())`.
