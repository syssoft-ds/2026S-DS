import csv
import json
import os
import re
import socket
import statistics
import subprocess
import time
from pathlib import Path



ROLE = "admin"
THIS_NODE_HOST = "192.168.178.25"
ADMIN_HOST = "192.168.178.25"
WORKER_ID = 1
WORKERS = 1

DEFAULT_RUNS_PER_NODE_COUNT = 10
BASE_PORT = 3000
MULTICAST_GROUP = "230.0.0.1"
MULTICAST_PORT = 45000
INITIAL_PROBABILITY = 0.5
SILENT_ROUNDS = 3
TIMEOUT_SECONDS = 120
PRE_RUN_PORT_WAIT_SECONDS = 30
NODE_STARTUP_TIMEOUT_SECONDS = 30
GRACEFUL_SHUTDOWN_SECONDS = 10
CLASS_PATH = "build/classes/java/main"
DEFAULT_HEAP_MB = 512
DEFAULT_CONTROL_HOST = "0.0.0.0"
DEFAULT_CONTROL_PORT = 47000

RESULT_PATTERN = re.compile(
    r"RESULT n=(?P<n>\d+) rounds=(?P<rounds>\d+) multicasts=(?P<multicasts>\d+) "
    r"minMs=(?P<min_ms>\d+\.\d+) avgMs=(?P<avg_ms>\d+\.\d+) maxMs=(?P<max_ms>\d+\.\d+)"
)


def main():
    repository_dir = Path(__file__).resolve().parents[1]
    project_dir = repository_dir / "sim4da-S26-pingpong"

    if ROLE == "admin":
        run_admin(repository_dir, project_dir)
    elif ROLE == "worker":
        run_worker(repository_dir, project_dir)
    else:
        raise RuntimeError('ROLE must be "admin" or "worker"')


def run_admin(repository_dir, project_dir):
    ensure_classes_exist(project_dir)
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    run_dir = repository_dir / "results" / f"tokenring-distributed-{timestamp}"
    run_dir.mkdir(parents=True, exist_ok=True)

    print(f"admin node host: {THIS_NODE_HOST}")
    print(f"waiting for {WORKERS} worker(s) on {DEFAULT_CONTROL_HOST}:{DEFAULT_CONTROL_PORT}")
    workers = accept_workers(DEFAULT_CONTROL_HOST, DEFAULT_CONTROL_PORT, WORKERS)
    node_count = WORKERS + 1
    host_by_node = {0: THIS_NODE_HOST}
    host_by_node.update({worker.worker_id: worker.node_host for worker in workers})
    print(f"ring node count: {node_count}")
    print(f"ring hosts by node: {host_by_node}")

    rows = []
    for run_index, repetition in enumerate(range(1, DEFAULT_RUNS_PER_NODE_COUNT + 1)):
        print(
            f"run {run_index}: n={node_count} physical nodes, repetition={repetition}, "
            f"per-node JVM heap=-Xmx{DEFAULT_HEAP_MB}m"
        )
        wait_for_udp_ports(BASE_PORT, [0], run_index)
        row = run_one_admin_experiment(
            project_dir=project_dir,
            run_dir=run_dir,
            run_index=run_index,
            repetition=repetition,
            node_count=node_count,
            workers=workers,
            host_by_node=host_by_node,
        )
        rows.append(row)
        print(row)

    for worker in workers:
        send_message(worker.file, {"type": "shutdown"})
        worker.close()

    raw_csv_path = run_dir / "raw-runs.csv"
    summary_csv_path = run_dir / "summary.csv"
    aggregate_rows = [summarize_rows(node_count, rows, DEFAULT_HEAP_MB)]
    write_summary_csv(raw_csv_path, rows)
    write_summary_csv(summary_csv_path, aggregate_rows)
    print_summary(aggregate_rows)
    print(f"raw runs: {raw_csv_path}")
    print(f"summary: {summary_csv_path}")


def run_one_admin_experiment(project_dir,
                             run_dir,
                             run_index,
                             repetition,
                             node_count,
                             workers,
                             host_by_node):
    processes = []
    log_files = []
    start_time = time.monotonic()

    try:
        for worker in workers:
            send_message(worker.file, {
                "type": "run",
                "runIndex": run_index,
                "nodeCount": node_count,
                "nodeIndex": worker.worker_id,
                "heapMb": DEFAULT_HEAP_MB,
                "basePort": BASE_PORT,
                "multicastGroup": MULTICAST_GROUP,
                "multicastPort": MULTICAST_PORT,
                "initialProbability": INITIAL_PROBABILITY,
                "silentRounds": SILENT_ROUNDS,
                "hostByNode": host_by_node,
            })

        wait_for_worker_ready(workers, run_index)

        initiator = start_node(
            project_dir=project_dir,
            run_dir=run_dir,
            run_index=run_index,
            node_index=0,
            node_count=node_count,
            heap_size=DEFAULT_HEAP_MB,
            starts_with_token=True,
            log_files=log_files,
            next_host=host_by_node[1 % node_count],
            base_port=BASE_PORT,
            multicast_group=MULTICAST_GROUP,
            multicast_port=MULTICAST_PORT,
            initial_probability=INITIAL_PROBABILITY,
            silent_rounds=SILENT_ROUNDS,
        )
        processes.append(initiator)

        status = wait_for_initiator(initiator, TIMEOUT_SECONDS)
        flush_files(log_files)
        result = parse_result(run_dir / f"run{run_index}-node0.log")
        if result is None and status == "ok":
            status = "no_result"

        duration_seconds = time.monotonic() - start_time
        cleanup_processes(processes)
        cleanup_workers(workers, run_index)
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
            "heapMbPerNode": DEFAULT_HEAP_MB,
            "logDir": str(run_dir),
        }
    finally:
        terminate_processes(processes)
        close_files(log_files)
        cleanup_workers(workers, run_index, fail_silent=True)


def run_worker(repository_dir, project_dir):
    ensure_classes_exist(project_dir)
    run_dir = repository_dir / "results" / "tokenring-distributed-worker"
    run_dir.mkdir(parents=True, exist_ok=True)
    processes = []
    log_files = []

    print(f"connecting to admin {ADMIN_HOST}:{DEFAULT_CONTROL_PORT} as worker {WORKER_ID}")
    with socket.create_connection((ADMIN_HOST, DEFAULT_CONTROL_PORT), timeout=30) as sock:
        file = sock.makefile("rw", encoding="utf-8", newline="\n")
        send_message(file, {"type": "hello", "workerId": WORKER_ID, "nodeHost": THIS_NODE_HOST})

        while True:
            message = read_message(file)
            if message["type"] == "shutdown":
                terminate_processes(processes)
                close_files(log_files)
                return
            if message["type"] == "cleanup":
                terminate_processes(processes)
                close_files(log_files)
                send_message(file, {"type": "cleaned", "runIndex": message["runIndex"]})
                continue
            if message["type"] != "run":
                raise RuntimeError(f"unsupported admin message: {message}")

            terminate_processes(processes)
            close_files(log_files)
            run_index = message["runIndex"]
            node_index = message["nodeIndex"]
            wait_for_udp_ports(message["basePort"], [node_index], run_index)
            try:
                host_by_node = normalized_host_map(message["hostByNode"])
                processes.append(start_node(
                    project_dir=project_dir,
                    run_dir=run_dir,
                    run_index=run_index,
                    node_index=node_index,
                    node_count=message["nodeCount"],
                    heap_size=message["heapMb"],
                    starts_with_token=False,
                    log_files=log_files,
                    next_host=host_by_node[(node_index + 1) % message["nodeCount"]],
                    base_port=message["basePort"],
                    multicast_group=message["multicastGroup"],
                    multicast_port=message["multicastPort"],
                    initial_probability=message["initialProbability"],
                    silent_rounds=message["silentRounds"],
                ))
                wait_for_nodes_ready(run_dir, run_index, [node_index], processes)
                send_message(file, {"type": "ready", "runIndex": run_index})
            except Exception as exception:
                terminate_processes(processes)
                close_files(log_files)
                send_message(file, {
                    "type": "failed",
                    "runIndex": run_index,
                    "error": str(exception),
                })


def accept_workers(control_host, control_port, expected_workers):
    workers = []
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((control_host, control_port))
        server.listen(expected_workers)
        while len(workers) < expected_workers:
            connection, address = server.accept()
            file = connection.makefile("rw", encoding="utf-8", newline="\n")
            hello = read_message(file)
            if hello["type"] != "hello":
                raise RuntimeError(f"expected worker hello from {address}, got {hello}")
            worker = WorkerConnection(
                worker_id=int(hello["workerId"]),
                node_host=hello["nodeHost"],
                connection=connection,
                file=file,
            )
            workers.append(worker)
            print(f"worker {worker.worker_id} connected from {address}, nodeHost={worker.node_host}")

    worker_ids = [worker.worker_id for worker in workers]
    if sorted(worker_ids) != list(range(1, expected_workers + 1)):
        raise RuntimeError(f"worker ids must be 1..{expected_workers}, got {worker_ids}")
    return sorted(workers, key=lambda worker: worker.worker_id)


class WorkerConnection:
    def __init__(self, worker_id, node_host, connection, file):
        self.worker_id = worker_id
        self.node_host = node_host
        self.connection = connection
        self.file = file

    def close(self):
        self.file.close()
        self.connection.close()


def send_message(file, message):
    file.write(json.dumps(message, separators=(",", ":")) + "\n")
    file.flush()


def read_message(file):
    line = file.readline()
    if not line:
        raise RuntimeError("control connection closed")
    return json.loads(line)


def wait_for_worker_ready(workers, run_index):
    for worker in workers:
        message = read_message(worker.file)
        if message.get("runIndex") != run_index:
            raise RuntimeError(f"worker {worker.worker_id} returned message for wrong run: {message}")
        if message["type"] == "failed":
            raise RuntimeError(f"worker {worker.worker_id} failed startup: {message['error']}")
        if message["type"] != "ready":
            raise RuntimeError(f"worker {worker.worker_id} returned unexpected message: {message}")


def cleanup_workers(workers, run_index, fail_silent=False):
    for worker in workers:
        try:
            send_message(worker.file, {"type": "cleanup", "runIndex": run_index})
            message = read_message(worker.file)
            if message["type"] != "cleaned":
                raise RuntimeError(f"worker {worker.worker_id} cleanup failed: {message}")
        except Exception:
            if not fail_silent:
                raise


def normalized_host_map(raw):
    return {int(key): value for key, value in raw.items()}


def ensure_classes_exist(project_dir):
    class_file = project_dir / CLASS_PATH / "tokenring" / "RingNode.class"
    if not class_file.exists():
        raise RuntimeError(
            f"{class_file} does not exist. Compile on this computer first, e.g. "
            f"from {project_dir}: .\\gradlew.bat compileJava"
        )


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


def summarize_rows(node_count, rows, heap_mb):
    node_rows = [row for row in rows if row["n"] == node_count]
    failed_runs = sum(1 for row in node_rows if row["status"] != "ok")

    return {
        "n": node_count,
        "runs": len(node_rows),
        "failedRuns": failed_runs,
        **metric_stats(node_rows, "rounds"),
        **metric_stats(node_rows, "multicasts"),
        **metric_stats(node_rows, "durationSeconds"),
        "heapMbPerNode": heap_mb,
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


def wait_for_udp_ports(base_port, node_indexes, run_index):
    deadline = time.monotonic() + PRE_RUN_PORT_WAIT_SECONDS
    busy_ports = find_busy_udp_ports(base_port, node_indexes)

    if busy_ports:
        print(f"run {run_index}: waiting for UDP ports to be released: {busy_ports[:8]}")

    while busy_ports and time.monotonic() < deadline:
        time.sleep(0.5)
        busy_ports = find_busy_udp_ports(base_port, node_indexes)

    if busy_ports:
        raise RuntimeError(
            f"UDP ports still in use before run {run_index}: {busy_ports[:20]}"
        )


def find_busy_udp_ports(base_port, node_indexes):
    busy_ports = []
    for node_index in node_indexes:
        port = base_port + node_index
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


def start_node(project_dir,
               run_dir,
               run_index,
               node_index,
               node_count,
               heap_size,
               starts_with_token,
               log_files,
               next_host,
               base_port,
               multicast_group,
               multicast_port,
               initial_probability,
               silent_rounds):
    local_port = base_port + node_index
    next_port = base_port + ((node_index + 1) % node_count)
    log_file = open(run_dir / f"run{run_index}-node{node_index}.log", "w", encoding="utf-8")
    log_files.append(log_file)

    command = [
        "java",
        f"-Xmx{heap_size}m",
        "-cp",
        CLASS_PATH,
        "tokenring.RingNode",
        f"N{node_index}",
        str(node_index),
        str(node_count),
        str(local_port),
        next_host,
        str(next_port),
        multicast_group,
        str(multicast_port),
        str(initial_probability),
        str(silent_rounds),
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


def wait_for_initiator(initiator, timeout_seconds):
    try:
        initiator.wait(timeout=timeout_seconds)
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

    processes.clear()


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
