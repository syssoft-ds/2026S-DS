import csv
import os
import re
import secrets
import statistics
import subprocess
import time
from pathlib import Path


NODE_COUNTS = [2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384,] # until 2exp14
RUNS_PER_NODE_COUNT = 10
INITIAL_PROBABILITY = 0.5
SILENT_ROUNDS = 3
TIMEOUT_SECONDS = 120
JAVA_HEAP = "16g"
CLASS_PATH = os.pathsep.join(["build/classes/java/main", "lib/sim4da.jar"])

RESULT_PATTERN = re.compile(
    r"RESULT n=(?P<n>\d+) rounds=(?P<rounds>\d+) multicasts=(?P<multicasts>\d+) "
    r"minMs=(?P<min_ms>\d+\.\d+) avgMs=(?P<avg_ms>\d+\.\d+) maxMs=(?P<max_ms>\d+\.\d+)"
)


def main():
    repository_dir = Path(__file__).resolve().parents[1]
    project_dir = repository_dir / "sim4da-S26-pingpong"
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_dir = repository_dir / "results" / f"sim4da-firework-{timestamp}"
    run_dir.mkdir(parents=True, exist_ok=True)

    rows = []
    run_index = 0
    for node_count in NODE_COUNTS:
        for repetition in range(1, RUNS_PER_NODE_COUNT + 1):
            print(f"run {run_index}: n={node_count}, repetition={repetition}")
            row = run_one_experiment(project_dir, run_dir, run_index, repetition, node_count)
            rows.append(row)
            print(row)
            run_index += 1

    raw_csv_path = run_dir / "raw-runs.csv"
    summary_csv_path = run_dir / "summary.csv"
    summary_rows = summarize_by_node_count(rows)
    write_csv(raw_csv_path, rows)
    write_csv(summary_csv_path, summary_rows)

    successful_ns = [row["n"] for row in rows if row["status"] == "ok"]
    max_successful_n = max(successful_ns) if successful_ns else None

    print_table(summary_rows)
    print(f"raw runs: {raw_csv_path}")
    print(f"summary: {summary_csv_path}")
    print(f"maximum successful n: {max_successful_n}")


def run_one_experiment(project_dir, run_dir, run_index, repetition, node_count):
    log_path = run_dir / f"run{run_index}-n{node_count}.log"
    seed = secrets.randbits(63)
    command = [
        "java",
        f"-Xmx{JAVA_HEAP}",
        "-cp",
        CLASS_PATH,
        "pingpong.FireworkSimulation",
        str(node_count),
        str(INITIAL_PROBABILITY),
        str(SILENT_ROUNDS),
        str(seed),
        "false",
    ]

    started = time.monotonic()
    with open(log_path, "w", encoding="utf-8") as log_file:
        try:
            completed = subprocess.run(
                command,
                cwd=project_dir,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=TIMEOUT_SECONDS,
                check=False,
            )
            status = "ok" if completed.returncode == 0 else f"exit_{completed.returncode}"
        except subprocess.TimeoutExpired:
            status = "timeout"

    duration_seconds = time.monotonic() - started
    result = parse_result(log_path)
    if result is None and status == "ok":
        status = "no_result"

    return {
        "runIndex": run_index,
        "n": node_count,
        "repetition": repetition,
        "status": status,
        "rounds": int(result["rounds"]) if result else None,
        "multicasts": int(result["multicasts"]) if result else None,
        "minMs": float(result["min_ms"]) if result else None,
        "avgMs": float(result["avg_ms"]) if result else None,
        "maxMs": float(result["max_ms"]) if result else None,
        "durationSeconds": round(duration_seconds, 3),
        "seed": seed,
        "logPath": str(log_path),
    }


def parse_result(log_path):
    if not log_path.exists():
        return None

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = RESULT_PATTERN.search(line)
        if match:
            return match.groupdict()
    return None


def summarize_by_node_count(rows):
    return [summarize_rows(node_count, rows) for node_count in NODE_COUNTS]


def summarize_rows(node_count, rows):
    node_rows = [row for row in rows if row["n"] == node_count]
    failed_runs = sum(1 for row in node_rows if row["status"] != "ok")

    return {
        "n": node_count,
        "runs": len(node_rows),
        "failedRuns": failed_runs,
        **metric_stats(node_rows, "rounds"),
        **metric_stats(node_rows, "multicasts"),
        **metric_stats(node_rows, "minMs"),
        **metric_stats(node_rows, "avgMs"),
        **metric_stats(node_rows, "maxMs"),
        **metric_stats(node_rows, "durationSeconds"),
    }


def metric_stats(rows, metric):
    values = [row[metric] for row in rows if row["status"] == "ok" and row[metric] is not None]
    if not values:
        return {
            f"{metric}Min": None,
            f"{metric}Max": None,
            f"{metric}Mean": None,
        }

    return {
        f"{metric}Min": min(values),
        f"{metric}Max": max(values),
        f"{metric}Mean": round(statistics.mean(values), 3),
    }


def write_csv(csv_path, rows):
    if not rows:
        return

    with open(csv_path, "w", encoding="utf-8", newline="") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def print_table(rows):
    if not rows:
        return

    fieldnames = list(rows[0].keys())
    widths = {
        field: max(len(field), *(len(str(row[field])) for row in rows))
        for field in fieldnames
    }
    print(" | ".join(field.ljust(widths[field]) for field in fieldnames))
    print("-+-".join("-" * widths[field] for field in fieldnames))
    for row in rows:
        print(" | ".join(str(row[field]).ljust(widths[field]) for field in fieldnames))


if __name__ == "__main__":
    main()
