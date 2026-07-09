# sim4da-S26 — Ping Pong

A tiny [sim4da](https://github.com/syssoft-ds/sim4da-S26) example: two nodes
("Ping" and "Pong") exchange messages for a configurable number of rounds and
then terminate.

## Layout

```
.
├── build.gradle.kts
├── settings.gradle.kts
├── lib/
│   └── sim4da.jar          # framework JAR, grabbed from the sim4da-S26 repo
└── src/
    └── pingpong/
        └── PingPongSimulation.java
```

`sim4da.jar` is the only dependency. Drop a newer version into `lib/` whenever
the upstream repo releases one — Gradle picks it up by path.

## Requirements

- JDK 25 (sim4da targets Java 25).

## Build & run

```
./gradlew run                     # 10 rounds (default)
./gradlew run --args="25"         # 25 rounds
```

Expected console output (for 3 rounds):

```
Ping got Pong(1) from Pong
Pong got Ping(1) from Ping
...
Ping got Pong(3) from Pong
Pong stopping
```

Every send and receive also lands in `sim4da-<PID>.log` in the working
directory, in the format documented by sim4da.

## How it works

- `Ping` extends `Node` and sends the first `Ping(1)` to `Pong`, then loops:
  on each `Pong(r)` it replies with `Ping(r+1)` until `r == rounds`, at which
  point it sends `Stop` and returns from `engage()`.
- `Pong` extends `Node`, replies to every `Ping(r)` with `Pong(r)`, and
  returns when it receives `Stop`.
- Termination is by message — no node calls `Simulator.stop()` — so
  `simulator.simulate()` returns naturally once both `engage()` methods have
  exited.
