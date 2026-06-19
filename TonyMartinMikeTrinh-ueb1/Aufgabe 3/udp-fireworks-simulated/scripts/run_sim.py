#!/usr/bin/env python3
"""Orchestriert die sim4da-Feuerwerk-Experimente (Aufgabe 3).

Anders als Aufgabe 1/2 laeuft hier der ganze Ring in **einer** JVM (sim4da, in-process,
ein Thread pro Knoten). Pro n wird also nur ein Prozess gestartet, dessen SUMMARY-Zeile
(von Knoten 0) abgefangen wird. Das maximale n ist hier durch Thread-/Speicher-Limits der
JVM begrenzt -> mit `--jvm-opts` (z.B. kleiner Stack `-Xss256k`, groesserer Heap `-Xmx2g`)
laesst es sich erhoehen.

Beispiele:
    python scripts/run_sim.py --n-list 2,4,8,16,32,64,128,256
    python scripts/run_sim.py                      # rampt 2,4,8,... bis zum Fehlschlag
    python scripts/run_sim.py --jvm-opts "-Xss220k -Xmx3g"
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
    """Sucht ein JDK 25 (sim4da.jar ist Java-25-Bytecode). Bevorzugt ein installiertes
    Temurin 25; faellt sonst auf 'java' im PATH zurueck. Per --java ueberschreibbar."""
    cands = sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-25*\bin\java.exe"), reverse=True)
    return cands[0] if cands else "java"


DEFAULT_JAVA = _default_java()

SUMMARY_RE = re.compile(r"\bSUMMARY\b(?P<rest>.*)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="sim4da-Feuerwerk Experimente (Aufgabe 3)")
    p.add_argument("--n-list", default=None,
                   help="Explizite n-Werte, z.B. '2,4,8,16,32'. Ohne Angabe: rampt 2,4,8,... bis Fehlschlag.")
    p.add_argument("--max-n-cap", type=int, default=8192, help="Obergrenze fuer die Ramp-Suche.")
    p.add_argument("--repeats", type=int, default=1, help="Wiederholungen pro n (stabilere Zeiten).")
    p.add_argument("--p0", type=float, default=0.5, help="Anfaengliche Zuendwahrscheinlichkeit.")
    p.add_argument("--k", type=int, default=3, help="Stille Runden bis Terminierung.")
    p.add_argument("--timeout", type=float, default=60.0, help="Watchdog je Lauf (Sekunden).")
    p.add_argument("--jvm-opts", default="-Xss256k -Xmx2g",
                   help="JVM-Optionen (kleiner Stack erlaubt mehr Knoten-Threads).")
    p.add_argument("--java", default=DEFAULT_JAVA, help="Pfad zum java-Executable (JDK 25!).")
    p.add_argument("--classes-dir", default=str(DEFAULT_CLASSES), help="Classpath mit firework.RingSimulation.")
    p.add_argument("--lib", default=str(DEFAULT_LIB), help="Pfad zu sim4da.jar.")
    p.add_argument("--out-dir", default=str(PROJECT_DIR / "results"), help="Ausgabeverzeichnis (CSV/Plots).")
    p.add_argument("--no-build", action="store_true", help="Gradle-Build ueberspringen.")
    p.add_argument("--no-plots", action="store_true", help="Keine Plots erzeugen.")
    return p.parse_args()


def ensure_build(args: argparse.Namespace) -> None:
    if args.no_build:
        return
    gradlew = PROJECT_DIR / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")
    print(f"[build] {gradlew} compileJava ...", flush=True)
    subprocess.run([str(gradlew), "-p", str(PROJECT_DIR), "compileJava", "--console=plain"],
                   cwd=str(PROJECT_DIR), check=True)


def run_one(args: argparse.Namespace, n: int) -> dict:
    """Startet eine Simulation der Ringgroesse n (ein JVM-Prozess) und liefert das Ergebnis-Dict."""
    classpath = args.classes_dir + os.pathsep + args.lib
    cmd = [args.java, *shlex.split(args.jvm_opts), "-cp", classpath,
           "firework.RingSimulation", str(n), str(args.p0), str(args.k)]
    result = {"n": n, "status": "timeout", "rounds": 0, "multicasts": 0,
              "rt_min_ms": 0.0, "rt_mean_ms": 0.0, "rt_max_ms": 0.0}
    try:
        proc = subprocess.run(cmd, cwd=str(PROJECT_DIR), stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        result["status"] = "timeout"
        print(f"  [n={n}] Watchdog ({args.timeout:.0f}s) ausgeloest -> Fehlschlag.", flush=True)
        return result

    summ = parse_summary(proc.stdout or "")
    if summ is None:
        result["status"] = "no_summary"
        tail = (proc.stderr or "").strip().splitlines()[-3:]
        if tail:
            print(f"  [n={n}] kein SUMMARY. stderr: {' | '.join(tail)}", flush=True)
    else:
        result["status"] = summ.get("status", "unknown")
        result["rounds"] = int(summ.get("rounds", 0))
        result["multicasts"] = int(summ.get("multicasts", 0))
        result["rt_min_ms"] = float(summ.get("rt_min_ms", 0.0))
        result["rt_mean_ms"] = float(summ.get("rt_mean_ms", 0.0))
        result["rt_max_ms"] = float(summ.get("rt_max_ms", 0.0))
    return result


def parse_summary(stdout: str) -> dict | None:
    for line in stdout.splitlines():
        m = SUMMARY_RE.search(line)
        if not m:
            continue
        d: dict = {}
        for tok in m.group("rest").split():
            if "=" in tok:
                key, _, val = tok.partition("=")
                d[key] = val
        return d
    return None


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
            print(f"[run] n={n} repeat={rep}/{args.repeats} ...", flush=True)
            res = run_one(args, n)
            res["repeat"] = rep
            rows.append(res)
            statuses.append(res["status"])
            print(f"  -> status={res['status']} rounds={res['rounds']} "
                  f"multicasts={res['multicasts']} rt(ms) "
                  f"min={res['rt_min_ms']:.3f} mean={res['rt_mean_ms']:.3f} max={res['rt_max_ms']:.3f}",
                  flush=True)
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

    csv_path = out_dir / "results.csv"
    fields = ["n", "repeat", "status", "rounds", "multicasts", "rt_min_ms", "rt_mean_ms", "rt_max_ms"]
    with csv_path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({key: r.get(key, "") for key in fields})

    print_table(rows)
    print(f"\n==> Maximales erfolgreiches n: {max_ok_n}")
    print(f"==> CSV: {csv_path}")

    if not args.no_plots:
        try:
            make_plots(rows, out_dir)
            print(f"==> Plots in: {out_dir}")
        except Exception as e:  # matplotlib fehlt o.ae. -> Experimente trotzdem gueltig
            print(f"[plots] uebersprungen: {e}")
    return 0


def aggregate(rows: list[dict]) -> dict[int, dict]:
    by_n: dict[int, list[dict]] = {}
    for r in rows:
        if r["status"] == "ok":
            by_n.setdefault(r["n"], []).append(r)
    agg = {}
    for n, rs in by_n.items():
        agg[n] = {key: statistics.mean(r[key] for r in rs)
                  for key in ("rounds", "multicasts", "rt_min_ms", "rt_mean_ms", "rt_max_ms")}
    return agg


def print_table(rows: list[dict]) -> None:
    agg = aggregate(rows)
    print("\n  n | runden | multicasts | rt_min_ms | rt_mean_ms | rt_max_ms")
    print("----+--------+------------+-----------+------------+----------")
    for n in sorted(agg):
        a = agg[n]
        print(f"{n:>4}|{a['rounds']:>8.1f}|{a['multicasts']:>12.1f}|"
              f"{a['rt_min_ms']:>11.3f}|{a['rt_mean_ms']:>12.3f}|{a['rt_max_ms']:>10.3f}")


def make_plots(rows: list[dict], out_dir: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    agg = aggregate(rows)
    if not agg:
        print("[plots] keine erfolgreichen Laeufe -> nichts zu plotten.")
        return
    ns = sorted(agg)

    def line_plot(ys: dict[str, list[float]], ylabel: str, title: str, fname: str) -> None:
        plt.figure()
        for label, vals in ys.items():
            plt.plot(ns, vals, marker="o", label=label)
        plt.xscale("log", base=2)
        plt.xlabel("n (Ringgroesse, log2)")
        plt.ylabel(ylabel)
        plt.title(title)
        if len(ys) > 1:
            plt.legend()
        plt.grid(True, which="both", linestyle=":")
        plt.tight_layout()
        plt.savefig(out_dir / fname, dpi=120)
        plt.close()

    line_plot({"Token-Runden": [agg[n]["rounds"] for n in ns]},
              "Token-Runden", "Token-Runden vs. n (sim4da)", "rounds_vs_n.png")
    line_plot({"gesendete Multicasts": [agg[n]["multicasts"] for n in ns]},
              "Multicasts", "Gesendete Multicasts vs. n (sim4da)", "multicasts_vs_n.png")
    line_plot({"min": [agg[n]["rt_min_ms"] for n in ns],
               "mittel": [agg[n]["rt_mean_ms"] for n in ns],
               "max": [agg[n]["rt_max_ms"] for n in ns]},
              "Rundenzeit (ms)", "Rundenzeit vs. n (sim4da)", "roundtime_vs_n.png")


if __name__ == "__main__":
    sys.exit(main())
