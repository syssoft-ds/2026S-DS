"""Manueller Starter fuer einen einzelnen Ring (mit sichtbaren Prozessausgaben).

Fuer die automatisierten Experimente mit wachsendem n siehe run_experiments.py.
"""

import argparse
import os
import subprocess
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
AUFGABE_1 = os.path.join(SCRIPT_DIR, "aufgabe_1.py")


def start_ring(n, p, k):
    processes = []

    for i in range(n):
        cmd = [
            sys.executable, AUFGABE_1,
            "--id", str(i),
            "--n", str(n),
            "--p", str(p),
            "--k", str(k),
        ]
        print(f"Starte P{i}")
        processes.append(subprocess.Popen(cmd))
        time.sleep(0.05)

    print("\nAlle Prozesse gestartet.\n")

    try:
        for proc in processes:
            proc.wait()
    except KeyboardInterrupt:
        print("Beende alle Prozesse...")
        for proc in processes:
            proc.terminate()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=4, help="Anzahl Prozesse")
    parser.add_argument("--p", type=float, default=0.5, help="Start-Zuendwahrscheinlichkeit")
    parser.add_argument("--k", type=int, default=3, help="stille Runden bis Terminierung")
    args = parser.parse_args()

    start_ring(args.n, args.p, args.k)
