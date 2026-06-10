# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository purpose

Portfolio exam submissions for the Master's course "Verteilte Systeme" (Distributed Systems), Summer 2026. Each exercise lives in its own directory (`exercise_XX/` for task descriptions, `sim4da-S26-*` for Gradle-based simulation projects).

## Build & run (Gradle projects)

All simulation projects use the Gradle wrapper. From inside the project directory:

```bash
./gradlew run                   # run with default args
./gradlew run --args="25"       # pass CLI arguments
./gradlew build                 # compile only
```

Requires **JDK 25** (configured via Gradle toolchain — no manual JAVA_HOME needed if JDK 25 is installed).

## sim4da framework

`sim4da.jar` is a local file dependency in `lib/` (not on Maven Central). To upgrade, replace the JAR — Gradle picks it up automatically.

**Core abstractions:**

- **`Node`** — extend this for every actor in the simulation; override `engage()` which runs in its own thread. Constructor arg is the node's name (also its address).
- **`Message`** — marker interface; implement with Java `record` types for pattern-matching in `switch`.
- **`send(message, targetName)`** — sends a message to a named node.
- **`receive()`** — blocks until a message arrives; returns `null` when the simulation is shutting down (use as the exit signal from `engage()`).
- **`Simulator.getInstance()`** — singleton; call `simulate()` to start all nodes, then `shutdown()` after it returns.

Termination is message-driven: once all `engage()` methods return, `simulate()` unblocks naturally. No node should call `Simulator.stop()` unless an abnormal abort is intended.

Log files named `sim4da-<PID>.log` are written to the working directory on each run.

## Exercise structure

| Path | Contents |
|------|----------|
| `exercise_XX/task_X.md` | Exercise description (German) |
| `sim4da-S26-<name>/` | Gradle project implementing the exercise |
| `Uebungsblaetter/` | Original PDF exercise sheets |
