import csv
import os
import re
import socket
import statistics
import subprocess
import time
from pathlib import Path


NODE_COUNTS = [896]
RUNS_PER_NODE_COUNT = 10
BASE_PORT = 3000 # the next occupied port is 5050 so this is plenty room for all nodes
MULTICAST_GROUP = "230.0.0.1"
MULTICAST_PORT = 45000
INITIAL_PROBABILITY = 0.5
SILENT_ROUNDS = 3
TIMEOUT_SECONDS = 120
PRE_RUN_PORT_WAIT_SECONDS = 30
NODE_STARTUP_TIMEOUT_SECONDS = 30
GRACEFUL_SHUTDOWN_SECONDS = 10
CLASS_PATH = "build/classes/java/main"
DEFAULT_HEAP_PERCENT = 50.0
TOTAL_AVAILABLE_HEAP = 16384
MIN_HEAP_MB = 8

RESULT_PATTERN = re.compile(
    r"RESULT n=(?P<n>\d+) rounds=(?P<rounds>\d+) multicasts=(?P<multicasts>\d+) "
    r"minMs=(?P<min_ms>\d+\.\d+) avgMs=(?P<avg_ms>\d+\.\d+) maxMs=(?P<max_ms>\d+\.\d+)"
)


def main():
    repository_dir = Path(__file__).resolve().parents[1]
    project_dir = repository_dir / "sim4da-S26-pingpong"
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_dir = repository_dir / "results" / f"tokenring-{timestamp}"
    run_dir.mkdir(parents=True, exist_ok=True)

    print(f"total available heap: {TOTAL_AVAILABLE_HEAP} MiB")
    print(f"heap budget: {DEFAULT_HEAP_PERCENT:g}% split across live node JVMs")

    rows = []
    run_index = 0
    for node_count in NODE_COUNTS:
        for repetition in range(1, RUNS_PER_NODE_COUNT + 1):
            system_heap_size = heap_mb_per_node(node_count)
            print(
                f"run {run_index}: n={node_count}, repetition={repetition}, "
                f"per-node JVM heap=-Xmx{system_heap_size}m"
            )
            wait_for_udp_ports(BASE_PORT, node_count, run_index)
            row = run_one_experiment(
                project_dir=project_dir,
                run_dir=run_dir,
                run_index=run_index,
                repetition=repetition,
                node_count=node_count,
                system_heap_size=system_heap_size,
            )
            rows.append(row)
            print(row)
            run_index += 1

    raw_csv_path = run_dir / "raw-runs.csv"
    aggregate_csv_path = run_dir / "summary.csv"
    aggregate_rows = summarize_by_node_count(rows)
    write_summary_csv(raw_csv_path, rows)
    write_summary_csv(aggregate_csv_path, aggregate_rows)

    successful_ns = [row["n"] for row in rows if row["status"] == "ok"]
    max_successful_n = max(successful_ns) if successful_ns else None

    print_summary(aggregate_rows)
    print(f"raw runs: {raw_csv_path}")
    print(f"summary: {aggregate_csv_path}")
    print(f"maximum successful n: {max_successful_n}")


def heap_mb_per_node(node_count):
    if DEFAULT_HEAP_PERCENT <= 0 or DEFAULT_HEAP_PERCENT > 100:
        raise ValueError("DEFAULT_HEAP_PERCENT must be greater than 0 and at most 100")

    experiment_budget_mb = int(TOTAL_AVAILABLE_HEAP * DEFAULT_HEAP_PERCENT / 100)
    return max(MIN_HEAP_MB, experiment_budget_mb // node_count)


def write_summary_csv(csv_path, rows):
    if not rows:
        return

    with open(csv_path, "w", encoding="utf-8", newline="") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def print_summary(rows):
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
        **metric_stats(node_rows, "durationSeconds"),
        "heapMbPerNode": heap_mb_per_node(node_count),
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


def wait_for_udp_ports(base_port, node_count, run_index):
    deadline = time.monotonic() + PRE_RUN_PORT_WAIT_SECONDS
    busy_ports = find_busy_udp_ports(base_port, node_count)

    if busy_ports:
        print(f"run {run_index}: waiting for UDP ports to be released: {busy_ports[:8]}")

    while busy_ports and time.monotonic() < deadline:
        time.sleep(0.5)
        busy_ports = find_busy_udp_ports(base_port, node_count)

    if busy_ports:
        raise RuntimeError(
            f"UDP ports still in use before run {run_index}: {busy_ports[:20]}"
        )


def find_busy_udp_ports(base_port, node_count):
    busy_ports = []
    for port in range(base_port, base_port + node_count):
        if not is_udp_port_free(port):
            busy_ports.append(port)
    return busy_ports


def is_udp_port_free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.bind(("", port))
            return True
        except OSError:
            return False


def run_one_experiment(project_dir,
                       run_dir,
                       run_index,
                       repetition,
                       node_count,
                       system_heap_size):
    processes = []
    log_files = []
    start_time = time.monotonic()

    try:
        for node_index in range(1, node_count):
            processes.append(start_node(
                project_dir, run_dir, run_index, node_index, node_count,
                system_heap_size, starts_with_token=False, log_files=log_files))

        wait_for_nodes_ready(run_dir, run_index, range(1, node_count), processes)

        initiator = start_node(
            project_dir, run_dir, run_index, 0, node_count,
            system_heap_size, starts_with_token=True, log_files=log_files)
        processes.append(initiator)

        status = wait_for_initiator(initiator)
        flush_files(log_files)
        result = parse_result(run_dir / f"run{run_index}-node0.log")
        if result is None and status == "ok":
            status = "no_result"

        duration_seconds = time.monotonic() - start_time

        cleanup_processes(processes)
        close_files(log_files)

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
            "heapMbPerNode": system_heap_size,
            "logDir": str(run_dir),
        }
    finally:
        terminate_processes(processes)
        close_files(log_files)


def start_node(project_dir,
               run_dir,
               run_index,
               node_index,
               node_count,
               system_heap_size,
               starts_with_token,
               log_files):
    local_port = BASE_PORT + node_index
    next_port = BASE_PORT + ((node_index + 1) % node_count)
    log_file = open(run_dir / f"run{run_index}-node{node_index}.log", "w", encoding="utf-8")
    log_files.append(log_file)

    command = [
        "java",
        f"-Xmx{system_heap_size}m",
        "-cp",
        CLASS_PATH,
        "tokenring.RingNode",
        f"N{node_index}",
        str(node_index),
        str(node_count),
        str(local_port),
        "127.0.0.1",
        str(next_port),
        MULTICAST_GROUP,
        str(MULTICAST_PORT),
        str(INITIAL_PROBABILITY),
        str(SILENT_ROUNDS),
    ]

    if starts_with_token:
        command.append("start")

    return subprocess.Popen(
        command,
        cwd=project_dir,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
    )


def wait_for_nodes_ready(run_dir, run_index, node_indexes, processes):
    pending = set(node_indexes)
    deadline = time.monotonic() + NODE_STARTUP_TIMEOUT_SECONDS

    while pending and time.monotonic() < deadline:
        failed_pids = [process.pid for process in processes if process.poll() is not None]
        if failed_pids:
            raise RuntimeError(f"run {run_index}: node process exited during startup: {failed_pids[:8]}")

        for node_index in list(pending):
            log_path = run_dir / f"run{run_index}-node{node_index}.log"
            if log_contains(log_path, " listening on port "):
                pending.remove(node_index)

        if pending:
            time.sleep(0.1)

    if pending:
        raise RuntimeError(f"run {run_index}: nodes did not become ready: {sorted(pending)[:20]}")


def log_contains(log_path, text):
    if not log_path.exists():
        return False

    return text in log_path.read_text(encoding="utf-8", errors="replace")


def wait_for_initiator(initiator):
    try:
        initiator.wait(timeout=TIMEOUT_SECONDS)
        return "ok"
    except subprocess.TimeoutExpired:
        return "timeout"


def cleanup_processes(processes):
    wait_for_processes(processes, GRACEFUL_SHUTDOWN_SECONDS)
    terminate_processes(processes)


def parse_result(log_path):
    if not log_path.exists():
        return None

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = RESULT_PATTERN.search(line)
        if match:
            return match.groupdict()

    return None


def wait_for_processes(processes, timeout_seconds):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if all(process.poll() is not None for process in processes):
            return
        time.sleep(0.1)


def terminate_processes(processes):
    for process in processes:
        if process.poll() is None:
            process.terminate()

    for process in processes:
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                force_kill_process_tree(process)


def force_kill_process_tree(process):
    if os.name != "nt":
        return

    subprocess.run(
        ["taskkill", "/F", "/T", "/PID", str(process.pid)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass


def close_files(files):
    while files:
        file = files.pop()
        file.close()


def flush_files(files):
    for file in files:
        file.flush()


if __name__ == "__main__":
    main()
