#!/usr/bin/env python3
import argparse, csv, glob, json, os, shutil, statistics, subprocess, sys, time

NODE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "firework.py")
STOP_SENDS = 3                   


def run_ring(n, p0, k, outdir, timeout):
    shutil.rmtree(outdir, ignore_errors=True)
    os.makedirs(outdir)
    # P0 wartet, bis alle Sockets gebunden sind
    delay = (max(2.0, 0.02 * n) if sys.platform == "win32"
             else max(1.0, 0.005 * n))
    # Idle-Timeout der Prozesse: muss Spawn-Phase
    # plus P0-Startverzoegerung sicher überdauern, sonst beenden sich früh gestartete Prozesse, bevor das Token ueberhaupt unterwegs ist
    spawn = 0.1 * n if sys.platform == "win32" else 0.01 * n
    idle = 30 + delay + spawn
    cmd = [sys.executable, NODE, "--n", str(n), "--p0", str(p0), "--k", str(k),
           "--outdir", outdir, "--startup-delay", str(delay),
           "--idle", str(round(idle, 1))]
    procs, t0 = [], time.monotonic() 
    auto = delay + spawn + idle + 60 + 0.05 * n   # grobe Schätzung der benötigten Zeit, damit die Messung nicht zu früh abbricht
    deadline = t0 + min(auto, timeout)            # hartes Zeitlimit, damit die Messung nicht ewig läuft, wenn etwas schiefgeht
    try:
        for i in list(range(1, n)) + [0]:        # P0 zuletzt: er injiziert das Token
            if time.monotonic() > deadline:       
                return False, time.monotonic() - t0
            err = open(os.path.join(outdir, f"node_{i:05d}.err"), "w")
            procs.append(subprocess.Popen(cmd + ["--id", str(i)],
                                          stdout=subprocess.DEVNULL, stderr=err))
            err.close()                           
        while time.monotonic() < deadline:
            if all(pr.poll() is not None for pr in procs):
                break
            time.sleep(0.2)
        else:
            return False, time.monotonic() - t0  
    finally:
        for pr in procs:
            if pr.poll() is None:
                pr.kill()
        for pr in procs:
            pr.wait()
    return all(pr.returncode == 0 for pr in procs), time.monotonic() - t0

# Hilfsfunktion: Gibt die erste Fehlermeldung zurück, die in den .err-Dateien im outdir gefunden wird (oder None, wenn keine Fehler gefunden werden)
def first_error(outdir):
    for f in sorted(glob.glob(os.path.join(outdir, "*.err"))):
        try:
            txt = open(f, errors="replace").read().strip()
        except OSError:
            continue
        if txt:
            return os.path.basename(f) + ": " + txt.splitlines()[-1]
    return None

# Alle Messwerte aus den node_*.json-Dateien im outdir sammeln und aggregieren.
def collect(outdir, n):
    nodes = []
    for i in range(n):
        path = os.path.join(outdir, f"node_{i:05d}.json")
        if not os.path.exists(path):
            return None
        with open(path) as f:
            nodes.append(json.load(f))
    p0 = next(s for s in nodes if s["id"] == 0)
    times = p0.get("round_times", [])
    rockets = sum(s["rockets_sent"] for s in nodes)
    received = sum(s["rockets_received"] for s in nodes)
    return {
        "rounds": p0.get("rounds", 0),
        "multicasts": rockets + STOP_SENDS,
        "retransmits": sum(s["retransmits"] for s in nodes),
        "delivery_pct": round(100 * received / (rockets * n), 1) if rockets else 100.0,
        "t_min_ms": round(min(times) * 1e3, 3) if times else None,
        "t_mean_ms": round(statistics.fmean(times) * 1e3, 3) if times else None,
        "t_max_ms": round(max(times) * 1e3, 3) if times else None,
    }

# Hauptfunktion: Führt die Messungen für verschiedene n durch, sammelt die Ergebnisse und speichert sie in einer CSV-Datei.
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start-n", type=int, default=2)
    ap.add_argument("--max-n", type=int, default=4096)
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--p0", type=float, default=1.0)
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--results-dir", default="results")
    ap.add_argument("--timeout", type=float, default=600,
                    help="hartes Zeitlimit pro Lauf in Sekunden (inkl. "
                         "Startphase aller Prozesse); Standard: 600")
    args = ap.parse_args()

    os.makedirs(args.results_dir, exist_ok=True)
    cols = ["n", "run", "ok", "wall_s", "rounds", "multicasts", "retransmits",
            "delivery_pct", "t_min_ms", "t_mean_ms", "t_max_ms"]
    csv_path = os.path.join(args.results_dir, "results.csv")
    max_ok, n = None, args.start_n

    # CSV inkrementell schreiben: Jede Zeile wird sofort gesichert, damit
    # auch bei Abbruch (Ctrl+C) keine Messwerte verloren gehen.
    with open(csv_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        try:
            while n <= args.max_n:
                all_ok = True
                for r in range(args.repeats):
                    outdir = os.path.join(args.results_dir, f"n{n:05d}_run{r}")
                    ok, wall = run_ring(n, args.p0, args.k, outdir, args.timeout)
                    agg = collect(outdir, n) if ok else None
                    w.writerow({"n": n, "run": r, "ok": ok,
                                "wall_s": round(wall, 2), **(agg or {})})
                    f.flush()
                    print(f"n={n:>5} run={r} ok={str(ok):5}" +
                          (f" rounds={agg['rounds']:>3} mc={agg['multicasts']:>5}"
                           f" rtx={agg['retransmits']:>4}"
                           f" dlv={agg['delivery_pct']:5.1f}%"
                           f" t={agg['t_min_ms']:.2f}/{agg['t_mean_ms']:.2f}/"
                           f"{agg['t_max_ms']:.2f}ms" if agg else
                           f"  GESCHEITERT nach {wall:.1f}s"), flush=True)
                    if not ok:
                        e = first_error(outdir)
                        if e:
                            print(f"        Fehler: {e}", flush=True)
                    all_ok &= ok
                if not all_ok:
                    break                  
                max_ok, n = n, n * 2
        except KeyboardInterrupt:
            print("\nAbgebrochen -- bisherige Ergebnisse sind gesichert.")

    print(f"\nMaximales erfolgreiches n: {max_ok}")
    print(f"Ergebnisse: {csv_path}")


if __name__ == "__main__":
    main()