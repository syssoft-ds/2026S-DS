#!/usr/bin/env python3
"""Orchestriert die sim4da-Konsistenz-Experimente (Aufgabe 4).

Wie Aufgabe 3 (ein JVM-Prozess je n, in-process), zusaetzlich wird je Lauf das
CONSISTENCY-Verdict von Knoten 0 erfasst. Mit `--inject-loss p` wird das UDP-Omission-
Fehlermodell simuliert (FIRE-Beobachtungen gehen mit Wahrscheinlichkeit p verloren) – damit
laesst sich die Inkonsistenz aus Aufgabe 1/2 reproduzieren und vom Detektor melden lassen.

Beispiele:
    python scripts/run_sim.py                                  # rampt 2,4,8,... (loss=0)
    python scripts/run_sim.py --n-list 8,32,64,128 --inject-loss 0.2
"""
from __future__ import annotations

import argparse
import csv
import glob
import os
import re
import shlex
import statistics
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CLASSES = PROJECT_DIR / "build" / "classes" / "java" / "main"
DEFAULT_LIB = PROJECT_DIR / "lib" / "sim4da.jar"


def _default_java() -> str:
    """Sucht ein JDK 25 (sim4da.jar ist Java-25-Bytecode). Bevorzugt installiertes Temurin 25."""
    cands = sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-25*\bin\java.exe"), reverse=True)
    return cands[0] if cands else "java"


DEFAULT_JAVA = _default_java()

SUMMARY_RE = re.compile(r"\bSUMMARY\b(?P<rest>.*)")
CONSISTENCY_RE = re.compile(r"\bCONSISTENCY\b(?P<rest>.*)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="sim4da-Konsistenz Experimente (Aufgabe 4)")
    p.add_argument("--n-list", default=None,
                   help="Explizite n-Werte. Ohne Angabe: rampt 2,4,8,... bis Fehlschlag.")
    p.add_argument("--max-n-cap", type=int, default=8192, help="Obergrenze fuer die Ramp-Suche.")
    p.add_argument("--repeats", type=int, default=1, help="Wiederholungen pro n.")
    p.add_argument("--p0", type=float, default=0.5, help="Anfaengliche Zuendwahrscheinlichkeit.")
    p.add_argument("--k", type=int, default=3, help="Stille Runden bis Terminierung.")
    p.add_argument("--inject-loss", type=float, default=0.0,
                   help="P(FIRE-Beobachtung verworfen) – simuliert UDP-Omission (0 = zuverlaessig).")
    p.add_argument("--timeout", type=float, default=60.0, help="Watchdog je Lauf (Sekunden).")
    p.add_argument("--jvm-opts", default="-Xss256k -Xmx2g", help="JVM-Optionen.")
    p.add_argument("--java", default=DEFAULT_JAVA, help="Pfad zum java-Executable (JDK 25!).")
    p.add_argument("--classes-dir", default=str(DEFAULT_CLASSES), help="Classpath mit firework.RingSimulation.")
    p.add_argument("--lib", default=str(DEFAULT_LIB), help="Pfad zu sim4da.jar.")
    p.add_argument("--out-dir", default=str(PROJECT_DIR / "results"), help="Ausgabeverzeichnis (CSV).")
    p.add_argument("--no-build", action="store_true", help="Gradle-Build ueberspringen.")
    return p.parse_args()


def ensure_build(args: argparse.Namespace) -> None:
    if args.no_build:
        return
    gradlew = PROJECT_DIR / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")
    print(f"[build] {gradlew} compileJava ...", flush=True)
    subprocess.run([str(gradlew), "-p", str(PROJECT_DIR), "compileJava", "--console=plain"],
                   cwd=str(PROJECT_DIR), check=True)


def _kv(rest: str) -> dict:
    d: dict = {}
    for tok in rest.split():
        if "=" in tok:
            key, _, val = tok.partition("=")
            d[key] = val
    return d


def run_one(args: argparse.Namespace, n: int) -> dict:
    classpath = args.classes_dir + os.pathsep + args.lib
    cmd = [args.java, *shlex.split(args.jvm_opts), "-cp", classpath,
           "firework.RingSimulation", str(n), str(args.p0), str(args.k), str(args.inject_loss)]
    result = {"n": n, "status": "timeout", "rounds": 0, "multicasts": 0,
              "rt_min_ms": 0.0, "rt_mean_ms": 0.0, "rt_max_ms": 0.0,
              "verdict": "-", "obs_min": 0, "obs_max": 0, "disagreeing": "-", "loss": args.inject_loss}
    try:
        proc = subprocess.run(cmd, cwd=str(PROJECT_DIR), stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        print(f"  [n={n}] Watchdog ({args.timeout:.0f}s) -> Fehlschlag.", flush=True)
        return result

    out = proc.stdout or ""
    for line in out.splitlines():
        m = SUMMARY_RE.search(line)
        if m:
            s = _kv(m.group("rest"))
            result["status"] = s.get("status", "unknown")
            result["rounds"] = int(s.get("rounds", 0))
            result["multicasts"] = int(s.get("multicasts", 0))
            result["rt_min_ms"] = float(s.get("rt_min_ms", 0.0))
            result["rt_mean_ms"] = float(s.get("rt_mean_ms", 0.0))
            result["rt_max_ms"] = float(s.get("rt_max_ms", 0.0))
        mc = CONSISTENCY_RE.search(line)
        if mc and "verdict=" in line:
            c = _kv(mc.group("rest"))
            result["verdict"] = c.get("verdict", "-")
            result["obs_min"] = int(c.get("observed_min", 0))
            result["obs_max"] = int(c.get("observed_max", 0))
            result["disagreeing"] = c.get("disagreeing", "-")
    if result["status"] == "timeout":
        result["status"] = "no_summary"
        tail = (proc.stderr or "").strip().splitlines()[-3:]
        if tail:
            print(f"  [n={n}] kein SUMMARY. stderr: {' | '.join(tail)}", flush=True)
    return result


def main() -> int:
    args = parse_args()
    ensure_build(args)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict] = []
    max_ok_n = 0

    def do_n(n: int) -> str:
        nonlocal max_ok_n
        statuses = []
        for rep in range(1, args.repeats + 1):
            print(f"[run] n={n} repeat={rep}/{args.repeats} loss={args.inject_loss} ...", flush=True)
            res = run_one(args, n)
            res["repeat"] = rep
            rows.append(res)
            statuses.append(res["status"])
            print(f"  -> status={res['status']} verdict={res['verdict']} "
                  f"fw={res['multicasts']} obs=[{res['obs_min']}..{res['obs_max']}] "
                  f"disagreeing={res['disagreeing']} rt_mean_ms={res['rt_mean_ms']:.3f}", flush=True)
        if any(s == "ok" for s in statuses):
            max_ok_n = max(max_ok_n, n)
            return "ok"
        return "fail"

    if args.n_list:
        for n in (int(x) for x in args.n_list.split(",") if x.strip()):
            do_n(n)
    else:
        n = 2
        while n <= args.max_n_cap:
            if do_n(n) != "ok":
                print(f"[ramp] n={n} fehlgeschlagen -> einmal wiederholen.", flush=True)
                if do_n(n) != "ok":
                    print(f"[ramp] n={n} erneut fehlgeschlagen -> Grenze erreicht.", flush=True)
                    break
            n *= 2

    suffix = "" if args.inject_loss == 0 else f"_loss{args.inject_loss}"
    csv_path = out_dir / f"results{suffix}.csv"
    fields = ["n", "repeat", "status", "verdict", "multicasts", "obs_min", "obs_max",
              "disagreeing", "loss", "rounds", "rt_min_ms", "rt_mean_ms", "rt_max_ms"]
    with csv_path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({key: r.get(key, "") for key in fields})

    print_table(rows)
    print(f"\n==> Maximales erfolgreiches n: {max_ok_n}")
    print(f"==> CSV: {csv_path}")
    return 0


def print_table(rows: list[dict]) -> None:
    ok = [r for r in rows if r["status"] == "ok"]
    if not ok:
        return
    print("\n  n | verdict      | fwTotal | obs_min | obs_max | disagreeing | rt_mean_ms")
    print("----+--------------+---------+---------+---------+-------------+-----------")
    for r in sorted(ok, key=lambda r: (r["n"], r["repeat"])):
        print(f"{r['n']:>4}|{r['verdict']:>14}|{r['multicasts']:>9}|{r['obs_min']:>9}|"
              f"{r['obs_max']:>9}|{str(r['disagreeing']):>13}|{r['rt_mean_ms']:>11.3f}")


if __name__ == "__main__":
    sys.exit(main())
