"""
Experiment-Script zu Aufgabe 1: baut Ringe wachsender Groesse (n = 2, 4, 8, ...)
automatisch auf, fuehrt sie aus und sammelt die Statistik ein.

Ablauf pro Experiment:
  1. Script bindet einen eigenen UDP-Port (RUNNER_PORT).
  2. n Prozesse (aufgabe_1.py) werden gestartet; jeder meldet READY, sobald
     seine Sockets gebunden sind.
  3. Sind alle n READY, schickt das Script GO an P0 -> das Token startet.
  4. P0 schickt am Ende die SUMMARY (Runden, Multicasts, Rundenzeiten) zurueck.

Schlaegt ein Schritt fehl (Timeout beim READY oder bei der SUMMARY), gilt das
Experiment als gescheitert; das zuletzt erfolgreiche n ist das "maximale n".
Die Ergebnisse werden als Tabelle ausgegeben und nach results_aufgabe1.csv
geschrieben.
"""

import argparse
import csv
import json
import os
import socket
import subprocess
import sys
import time

LOCALHOST = "127.0.0.1"
RUNNER_PORT = 19999
BASE_TOKEN_PORT = 20000
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
AUFGABE_1 = os.path.join(SCRIPT_DIR, "aufgabe_1.py")


def run_ring(n, p, k, verbose=False):
    """Fuehrt ein Experiment mit n Prozessen aus. Liefert die SUMMARY oder None."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((LOCALHOST, RUNNER_PORT))

    processes = []
    try:
        for i in range(n):
            cmd = [
                sys.executable, AUFGABE_1,
                "--id", str(i),
                "--n", str(n),
                "--p", str(p),
                "--k", str(k),
                "--base-port", str(BASE_TOKEN_PORT),
                "--runner-port", str(RUNNER_PORT),
                # Timeout muss die Startphase abdecken: das Spawnen von n
                # Prozessen dauert bei grossem n laenger als der Default
                "--timeout", str(max(30.0, 0.2 * n)),
            ]
            if not verbose:
                cmd.append("--quiet")
            processes.append(subprocess.Popen(cmd))

        # Auf READY aller n Prozesse warten
        sock.settimeout(10 + 0.1 * n)
        ready = set()
        while len(ready) < n:
            data, _ = sock.recvfrom(4096)
            msg = json.loads(data.decode())
            if msg.get("type") == "READY":
                ready.add(msg["id"])

        # Startschuss an P0
        sock.sendto(json.dumps({"type": "GO"}).encode(),
                    (LOCALHOST, BASE_TOKEN_PORT))
        t_start = time.perf_counter()

        # Auf SUMMARY von P0 warten
        sock.settimeout(60 + 0.5 * n)
        while True:
            data, _ = sock.recvfrom(65535)
            msg = json.loads(data.decode())
            if msg.get("type") == "SUMMARY":
                summary = msg
                break

        summary["total_time_s"] = time.perf_counter() - t_start

        for proc in processes:
            proc.wait(timeout=15)
        return summary

    except (socket.timeout, subprocess.TimeoutExpired):
        return None
    finally:
        for proc in processes:
            if proc.poll() is None:
                proc.kill()
        sock.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--p", type=float, default=0.5, help="Start-Zuendwahrscheinlichkeit")
    parser.add_argument("--k", type=int, default=3, help="stille Runden bis Terminierung")
    parser.add_argument("--start-n", type=int, default=2, help="Startwert fuer n")
    parser.add_argument("--max-n", type=int, default=1024, help="Obergrenze fuer n")
    parser.add_argument("--repeats", type=int, default=3, help="Wiederholungen pro n")
    parser.add_argument("--verbose", action="store_true", help="Prozessausgaben anzeigen")
    args = parser.parse_args()

    results = []
    n = args.start_n
    while n <= args.max_n:
        print(f"\n=== Experiment: n = {n} (x{args.repeats}) ===")
        failed = False
        for run in range(args.repeats):
            summary = run_ring(n, args.p, args.k, verbose=args.verbose)
            if summary is None:
                print(f"  Lauf {run + 1}: FEHLGESCHLAGEN (Timeout)")
                failed = True
                break
            summary["run"] = run + 1
            results.append(summary)
            print(f"  Lauf {run + 1}: {summary['rounds']} Runden, "
                  f"{summary['total_multicasts']} Multicasts, "
                  f"Rundenzeit min/avg/max = "
                  f"{summary['round_time_min_ms']:.2f}/"
                  f"{summary['round_time_avg_ms']:.2f}/"
                  f"{summary['round_time_max_ms']:.2f} ms")
            time.sleep(0.5)  # Ports/Sockets sauber freigeben lassen
        if failed:
            break
        n *= 2

    if not results:
        print("\nKein Experiment erfolgreich.")
        return

    max_n = max(r["n"] for r in results)
    print(f"\nMaximal erfolgreiches n: {max_n}")

    # CSV schreiben
    csv_path = os.path.join(SCRIPT_DIR, "results_aufgabe1.csv")
    fields = ["n", "run", "k", "p_start", "rounds", "total_multicasts",
              "round_time_min_ms", "round_time_avg_ms", "round_time_max_ms",
              "total_time_s"]
    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(results)
    print(f"Ergebnisse geschrieben nach {csv_path}")

    # Zusammenfassung pro n (Mittel ueber die Wiederholungen)
    print(f"\n{'n':>6} | {'Runden':>7} | {'Multicasts':>10} | "
          f"{'min ms':>8} | {'avg ms':>8} | {'max ms':>8}")
    print("-" * 62)
    for n_val in sorted({r["n"] for r in results}):
        rows = [r for r in results if r["n"] == n_val]
        avg = lambda key: sum(r[key] for r in rows) / len(rows)
        print(f"{n_val:>6} | {avg('rounds'):>7.1f} | {avg('total_multicasts'):>10.1f} | "
              f"{avg('round_time_min_ms'):>8.2f} | {avg('round_time_avg_ms'):>8.2f} | "
              f"{avg('round_time_max_ms'):>8.2f}")


if __name__ == "__main__":
    main()
