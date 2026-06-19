#!/usr/bin/env python3
"""Orchestriert die UDP-Feuerwerk-Experimente (Aufgabe 1).

Startet fuer wachsendes n jeweils n RingNode-Prozesse auf localhost, faengt die
SUMMARY-Zeile von Knoten 0 ab und sammelt die Statistik (Token-Runden, gesendete
Multicasts, min/mittlere/max Rundenzeit). Ermittelt das maximale n, das gerade
noch laeuft, schreibt results.csv und (optional) matplotlib-Plots.

Robustheit (Hybrid): kein Token-Retransmit; jeder Knoten beendet sich bei
Leerlauf selbst, und hier sorgt ein Watchdog-Timeout pro Lauf fuer Aufraeumen.

Beispiele:
    python scripts/run_experiments.py --n-list 2,4,8,16,32
    python scripts/run_experiments.py            # rampt 2,4,8,... bis zum Fehlschlag
"""
from __future__ import annotations

import argparse
import csv
import re
import shlex
import socket
import statistics
import subprocess
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CLASSES = PROJECT_DIR / "build" / "classes" / "java" / "main"

SUMMARY_RE = re.compile(r"\bSUMMARY\b(?P<rest>.*)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="UDP-Feuerwerk Experimente (Aufgabe 1)")
    p.add_argument("--n-list", default=None,
                   help="Explizite n-Werte, z.B. '2,4,8,16,32'. Ohne Angabe: rampt 2,4,8,... bis Fehlschlag.")
    p.add_argument("--max-n-cap", type=int, default=4096, help="Obergrenze fuer die Ramp-Suche.")
    p.add_argument("--repeats", type=int, default=1, help="Wiederholungen pro n (fuer stabilere Zeiten).")
    p.add_argument("--p0", type=float, default=0.5, help="Anfaengliche Zuendwahrscheinlichkeit.")
    p.add_argument("--k", type=int, default=3, help="Stille Runden bis Terminierung.")
    p.add_argument("--base-port", type=int, default=5000, help="Unicast-Basisport (Knoten i -> base+i).")
    p.add_argument("--mc-addr", default="230.0.0.1", help="Multicast-Gruppenadresse.")
    p.add_argument("--mc-port", type=int, default=4446, help="Multicast-Port.")
    p.add_argument("--idle-timeout-ms", type=int, default=8000,
                   help="Leerlauf-Timeout je Knoten (Runden dauern bei grossem n Sekunden).")
    p.add_argument("--ttl", type=int, default=1, help="Multicast-TTL (0=strikt host-lokal, 1=lokales Subnetz).")
    p.add_argument("--timeout", type=float, default=30.0, help="Watchdog je Lauf (Sek.) zusaetzlich zum Startbudget.")
    p.add_argument("--jvm-opts", default="-Xmx32m -Xss512k", help="JVM-Optionen je Knoten (mehr JVMs pro Maschine).")
    p.add_argument("--java", default="java", help="Pfad zum java-Executable.")
    p.add_argument("--classes-dir", default=str(DEFAULT_CLASSES), help="Classpath mit firework.RingNode.")
    p.add_argument("--out-dir", default=str(PROJECT_DIR / "results"), help="Ausgabeverzeichnis (CSV/Plots).")
    p.add_argument("--verbose-nodes", action="store_true", help="Knoten schreiben per-Node-Logs (langsamer).")
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


def startup_ms_for(n: int) -> int:
    """Startbudget skaliert mit n (Starten vieler JVMs dauert)."""
    return max(20_000, n * 150)


def udp_port_free(port: int) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def find_free_base(start: int, n: int, limit: int = 60_000) -> int | None:
    """Sucht einen zusammenhaengenden, freien UDP-Portblock [base, base+n) ab `start`.

    Vermeidet von anderen Diensten/Restprozessen belegte Ports, die sonst eine
    BindException und damit kuenstliches startup_failed ausloesen wuerden.
    """
    base = start
    while base + n + 16 < limit:
        if all(udp_port_free(base + i) for i in range(n)):
            return base
        base += n + 16
    return None


def launch_node(args: argparse.Namespace, rank: int, n: int, base_port: int, idle_ms: int,
                startup_ms: int, capture: bool) -> subprocess.Popen:
    cmd = [args.java, *shlex.split(args.jvm_opts), "-cp", args.classes_dir, "firework.RingNode",
           str(rank), str(n), str(base_port), args.mc_addr, str(args.mc_port),
           str(args.p0), str(args.k), str(idle_ms), str(args.ttl),
           "true" if args.verbose_nodes else "false", str(startup_ms)]
    out = subprocess.PIPE if capture else subprocess.DEVNULL
    err = subprocess.PIPE if capture else subprocess.DEVNULL
    return subprocess.Popen(cmd, cwd=str(PROJECT_DIR), stdout=out, stderr=err, text=True)


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


def run_one(args: argparse.Namespace, n: int, base_port: int) -> dict:
    """Startet einen Ring der Groesse n und liefert das Ergebnis-Dict."""
    startup_ms = startup_ms_for(n)
    # Idle-Timeout muss die (mit n wachsende) Steady-State-Rundenzeit ueberschreiten, sonst
    # entstuenden falsche Stalls. Skaliert daher mit n, mit dem CLI-Wert als Untergrenze.
    idle_ms = max(args.idle_timeout_ms, n * 120)
    watchdog_s = startup_ms / 1000.0 + args.timeout
    procs: list[subprocess.Popen] = []

    # Knoten 0 zuerst (sammelt Barriere), dann die Follower. Reihenfolge unkritisch
    # (READY-Resend + Barriere fangen Start-Races ab).
    proc0 = launch_node(args, 0, n, base_port, idle_ms, startup_ms, capture=True)
    procs.append(proc0)
    for rank in range(1, n):
        procs.append(launch_node(args, rank, n, base_port, idle_ms, startup_ms, capture=False))

    result = {"n": n, "status": "timeout", "rounds": 0, "multicasts": 0,
              "rt_min_ms": 0.0, "rt_mean_ms": 0.0, "rt_max_ms": 0.0}
    try:
        out, err = proc0.communicate(timeout=watchdog_s)
        summ = parse_summary(out or "")
        if summ is None:
            result["status"] = "no_summary"
            tail = (err or "").strip().splitlines()[-3:]
            if tail:
                print(f"  [n={n}] kein SUMMARY. stderr: {' | '.join(tail)}", flush=True)
        else:
            result["status"] = summ.get("status", "unknown")
            result["rounds"] = int(summ.get("rounds", 0))
            result["multicasts"] = int(summ.get("multicasts", 0))
            result["rt_min_ms"] = float(summ.get("rt_min_ms", 0.0))
            result["rt_mean_ms"] = float(summ.get("rt_mean_ms", 0.0))
            result["rt_max_ms"] = float(summ.get("rt_max_ms", 0.0))
    except subprocess.TimeoutExpired:
        result["status"] = "timeout"
        print(f"  [n={n}] Watchdog ({watchdog_s:.0f}s) ausgeloest -> Lauf gilt als Fehlschlag.", flush=True)
    finally:
        for pr in procs:
            if pr.poll() is None:
                pr.kill()
        for pr in procs:
            try:
                pr.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
    return result


def n_sequence(args: argparse.Namespace):
    """Liefert (modus, iterierbare n). 'list' = feste Liste, 'ramp' = verdoppeln bis Fehlschlag."""
    if args.n_list:
        return "list", [int(x) for x in args.n_list.split(",") if x.strip()]
    return "ramp", None


def main() -> int:
    args = parse_args()
    ensure_build(args)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    rows: list[dict] = []
    mode, fixed = n_sequence(args)
    max_ok_n = 0

    # Jeder Lauf bekommt einen disjunkten Portbereich, damit noch nicht freigegebene Ports
    # verwaister Prozesse aus dem Vorlauf keine BindException erzwingen (Harness-Robustheit).
    port_cursor = [args.base_port]

    def next_base_port(n: int) -> int:
        bp = find_free_base(port_cursor[0], n)
        if bp is None:                       # nichts frei ab cursor → von vorn suchen
            bp = find_free_base(args.base_port, n)
        if bp is None:
            raise RuntimeError(f"kein freier Portblock der Groesse {n} gefunden")
        port_cursor[0] = bp + max(n, 64) + 16
        return bp

    def do_n(n: int) -> str:
        nonlocal max_ok_n
        statuses = []
        for rep in range(1, args.repeats + 1):
            base_port = next_base_port(n)
            print(f"[run] n={n} repeat={rep}/{args.repeats} base_port={base_port} ...", flush=True)
            res = run_one(args, n, base_port)
            res["repeat"] = rep
            rows.append(res)
            statuses.append(res["status"])
            print(f"  -> status={res['status']} rounds={res['rounds']} "
                  f"multicasts={res['multicasts']} rt(ms) "
                  f"min={res['rt_min_ms']:.3f} mean={res['rt_mean_ms']:.3f} max={res['rt_max_ms']:.3f}",
                  flush=True)
            time.sleep(1.0)  # Ports/Multicast-Gruppe freigeben lassen
        if any(s == "ok" for s in statuses):
            max_ok_n = max(max_ok_n, n)
            return "ok"
        return "fail"

    if mode == "list":
        for n in fixed:
            do_n(n)
    else:
        n = 2
        while n <= args.max_n_cap:
            outcome = do_n(n)
            if outcome != "ok":
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
            w.writerow({k: r.get(k, "") for k in fields})

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
    """Mittelt erfolgreiche Wiederholungen je n."""
    by_n: dict[int, list[dict]] = {}
    for r in rows:
        if r["status"] == "ok":
            by_n.setdefault(r["n"], []).append(r)
    agg = {}
    for n, rs in by_n.items():
        agg[n] = {
            "rounds": statistics.mean(r["rounds"] for r in rs),
            "multicasts": statistics.mean(r["multicasts"] for r in rs),
            "rt_min_ms": statistics.mean(r["rt_min_ms"] for r in rs),
            "rt_mean_ms": statistics.mean(r["rt_mean_ms"] for r in rs),
            "rt_max_ms": statistics.mean(r["rt_max_ms"] for r in rs),
        }
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
              "Token-Runden", "Token-Runden vs. n", "rounds_vs_n.png")
    line_plot({"gesendete Multicasts": [agg[n]["multicasts"] for n in ns]},
              "Multicasts", "Gesendete Multicasts vs. n", "multicasts_vs_n.png")
    line_plot({"min": [agg[n]["rt_min_ms"] for n in ns],
               "mittel": [agg[n]["rt_mean_ms"] for n in ns],
               "max": [agg[n]["rt_max_ms"] for n in ns]},
              "Rundenzeit (ms)", "Rundenzeit vs. n", "roundtime_vs_n.png")


if __name__ == "__main__":
    sys.exit(main())
