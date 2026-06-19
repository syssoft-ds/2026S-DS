"""
Automation script for Token Ring experiments.

Starts n processes for increasing n, collects statistics from process 0,
and prints a results table. Also determines the maximum working n.
"""

import subprocess
import sys
import time
import re
import os
from dataclasses import dataclass
from typing import Optional

PYTHON = sys.executable
PROCESS_SCRIPT = os.path.join(os.path.dirname(__file__), 'process.py')

BASE_PORT = 50000
MCAST_GROUP = '239.0.0.1'
MCAST_PORT = 55000

INITIAL_P = 0.5
K = 3
EXPERIMENT_TIMEOUT = 300  # seconds per experiment (generous for large n)


@dataclass
class Stats:
    n: int
    rounds: int
    multicasts: int
    min_rt: float
    mean_rt: float
    max_rt: float


def run_experiment(n: int, p: float = INITIAL_P, k: int = K) -> Optional[Stats]:
    """
    Launch n processes, wait for completion, parse and return stats.
    Processes are started in reverse order so process 0 (which initiates
    the token) is started last, giving others time to bind their sockets.
    """
    # Time for all n-1 processes to start (0.02s each) plus buffer
    startup_delay = n * 0.02 + 0.5
    # Socket timeout must cover: time to start all procs + startup_delay + full ring traversal + buffer
    sock_timeout = startup_delay + n * 0.01 + 60

    procs: list[subprocess.Popen] = []

    # Start processes n-1 down to 1 first
    for i in range(n - 1, -1, -1):
        cmd = [
            PYTHON, PROCESS_SCRIPT,
            '--id', str(i),
            '--n', str(n),
            '--p', str(p),
            '--k', str(k),
            '--base-port', str(BASE_PORT),
            '--mcast-group', MCAST_GROUP,
            '--mcast-port', str(MCAST_PORT),
            '--timeout', str(sock_timeout),
        ]
        if i == 0:
            cmd += ['--start-delay', str(startup_delay)]

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        procs.append((i, proc))
        time.sleep(0.02)  # stagger process starts

    # Wait for all, collecting stdout
    deadline = time.time() + EXPERIMENT_TIMEOUT
    outputs: dict[int, tuple[str, str]] = {}
    for pid, proc in procs:
        remaining = deadline - time.time()
        if remaining <= 0:
            proc.kill()
            outputs[pid] = ('', 'TIMEOUT')
            continue
        try:
            stdout, stderr = proc.communicate(timeout=remaining)
            outputs[pid] = (stdout, stderr)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, stderr = proc.communicate()
            outputs[pid] = (stdout, f'TIMEOUT: {stderr}')

    # Parse STATS line from process 0 output
    stdout0, stderr0 = outputs.get(0, ('', ''))
    pattern = (
        r'STATS n=(\d+) rounds=(\d+) multicasts=(\d+) '
        r'min_rt=([\d.]+) mean_rt=([\d.]+) max_rt=([\d.]+)'
    )
    for line in stdout0.splitlines():
        m = re.search(pattern, line)
        if m:
            return Stats(
                n=int(m.group(1)),
                rounds=int(m.group(2)),
                multicasts=int(m.group(3)),
                min_rt=float(m.group(4)),
                mean_rt=float(m.group(5)),
                max_rt=float(m.group(6)),
            )

    # If no stats found, print stderr for debugging
    if stderr0:
        print(f'  [P0 stderr]: {stderr0.strip()[:200]}', file=sys.stderr)
    return None


def print_table(results: list[Stats]) -> None:
    header = (
        f'{"n":>6}  {"rounds":>7}  {"multicasts":>11}  '
        f'{"min_rt(ms)":>11}  {"mean_rt(ms)":>12}  {"max_rt(ms)":>11}'
    )
    sep = '-' * len(header)
    print('\n=== Experiment Results ===')
    print(header)
    print(sep)
    for r in results:
        print(
            f'{r.n:>6}  {r.rounds:>7}  {r.multicasts:>11}  '
            f'{r.min_rt * 1000:>11.3f}  {r.mean_rt * 1000:>12.3f}  {r.max_rt * 1000:>11.3f}'
        )
    print(sep)


def main() -> None:
    ns = [2, 4, 8, 16, 32, 64]
    results: list[Stats] = []
    max_n = 0

    print(f'Token Ring UDP Experiments  (p={INITIAL_P}, k={K})')
    print(f'{"n":>6}  Status')
    print('-' * 40)

    for n in ns:
        print(f'{n:>6}  running...', end='  ', flush=True)
        t0 = time.time()
        stats = run_experiment(n)
        elapsed = time.time() - t0

        if stats:
            results.append(stats)
            max_n = n
            print(f'OK  ({elapsed:.1f}s,  {stats.rounds} rounds,  {stats.multicasts} multicasts)')
        else:
            print(f'FAILED / TIMEOUT after {elapsed:.1f}s')
            break  # Stop at first failure

        time.sleep(0.5)  # brief pause between experiments

    print(f'\nMaximum successful n: {max_n}')
    if results:
        print_table(results)


if __name__ == '__main__':
    main()
