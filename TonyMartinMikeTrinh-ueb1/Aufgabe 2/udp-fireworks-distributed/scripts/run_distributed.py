#!/usr/bin/env python3
"""Config-Generator + Start-Helfer fuer Aufgabe 2 (verteiltes UDP-Feuerwerk).

Anders als Aufgabe 1 laeuft hier je ein Prozess auf einem eigenen, realen Rechner. Dieses
Skript kann die Ringe daher nicht selbst auf allen Maschinen starten (kein Zugriff). Es

  1. erzeugt aus --hosts die Membership-Datei members.txt (rank host port),
  2. druckt fuer JEDEN Knoten den fertigen Startbefehl (PowerShell fuer den PC,
     bash/Termux fuers Handy) zum Kopieren,
  3. startet optional mit --launch-local den lokalen Knoten (Default: rank 0 = Initiator),
     faengt dessen SUMMARY ab und schreibt results_distributed.csv.

Nur Python-Standardbibliothek (keine neue Dependency).

Beispiele:
    # members.txt erzeugen + Startbefehle anzeigen (PC=rank0, Handy=rank1):
    python scripts/run_distributed.py --hosts 192.168.1.50:6000,192.168.1.77:6000

    # zusaetzlich Knoten 0 lokal starten und Statistik sammeln:
    python scripts/run_distributed.py --hosts 192.168.1.50,192.168.1.77 --launch-local
"""
from __future__ import annotations

import argparse
import csv
import re
import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_JAR = PROJECT_DIR / "build" / "libs" / "udp-fireworks-distributed.jar"
DEFAULT_MEMBERS = PROJECT_DIR / "members.txt"
JAR_NAME = "udp-fireworks-distributed.jar"   # Dateiname, wie er auf dem Handy liegt

# SUMMARY-Format identisch zu Aufgabe 1 (Knoten 0 gibt es aus).
SUMMARY_RE = re.compile(r"\bSUMMARY\b(?P<rest>.*)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Verteiltes UDP-Feuerwerk (Aufgabe 2)")
    p.add_argument("--hosts", default=None,
                   help="Knoten in rank-Reihenfolge: 'host[:port],host[:port],...'. "
                        "Ohne Port wird --port verwendet. Schreibt members.txt.")
    p.add_argument("--port", type=int, default=6000, help="Default-Port, falls in --hosts keiner steht.")
    p.add_argument("--members", default=str(DEFAULT_MEMBERS),
                   help="members.txt (wird aus --hosts geschrieben bzw. sonst gelesen).")
    p.add_argument("--p0", type=float, default=0.5, help="Anfaengliche Zuendwahrscheinlichkeit.")
    p.add_argument("--k", type=int, default=3, help="Stille Runden bis Terminierung.")
    p.add_argument("--mc-addr", default="230.0.0.1", help="Multicast-Gruppe (nur bei --bcast multicast).")
    p.add_argument("--mc-port", type=int, default=4446, help="Control-/Broadcast-Port.")
    p.add_argument("--bcast", choices=["unicast", "multicast"], default="unicast",
                   help="Broadcast-Abbildung: unicast (n Unicasts, Android-tauglich) oder multicast.")
    p.add_argument("--idle-timeout-ms", type=int, default=8000, help="Leerlauf-Timeout je Knoten.")
    p.add_argument("--ttl", type=int, default=1, help="Multicast-TTL (1 = lokales Subnetz).")
    p.add_argument("--startup-timeout-ms", type=int, default=30000, help="Wartebudget bis der Ring steht.")
    p.add_argument("--verbose-nodes", action="store_true", help="Knoten schreiben per-Node-Logs.")
    p.add_argument("--jar", default=str(DEFAULT_JAR), help="Pfad zur JAR (lokaler Start).")
    p.add_argument("--java", default="java", help="java-Executable (lokaler Start).")
    p.add_argument("--launch-local", action="store_true",
                   help="Lokalen Knoten als Subprozess starten und SUMMARY erfassen.")
    p.add_argument("--local-rank", type=int, default=0, help="Welcher rank lokal laeuft (Default 0).")
    p.add_argument("--timeout", type=float, default=60.0,
                   help="Watchdog (Sek.) zusaetzlich zum Startbudget beim lokalen Start.")
    p.add_argument("--no-build", action="store_true", help="Gradle-Build vor --launch-local ueberspringen.")
    p.add_argument("--out-dir", default=str(PROJECT_DIR), help="Ablage fuer results_distributed.csv.")
    return p.parse_args()


def parse_hosts(spec: str, default_port: int) -> list[tuple[str, int]]:
    members: list[tuple[str, int]] = []
    for entry in spec.split(","):
        entry = entry.strip()
        if not entry:
            continue
        if ":" in entry:
            host, _, port = entry.rpartition(":")
            members.append((host.strip(), int(port)))
        else:
            members.append((entry, default_port))
    if len(members) < 2:
        raise SystemExit("Fehler: mindestens 2 Knoten noetig (--hosts host1,host2[,...]).")
    return members


def write_members(path: Path, members: list[tuple[str, int]]) -> None:
    lines = ["# rank host port  (erzeugt von run_distributed.py)"]
    for rank, (host, port) in enumerate(members):
        lines.append(f"{rank} {host} {port}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_members(path: Path) -> list[tuple[str, int]]:
    if not path.exists():
        raise SystemExit(f"Fehler: {path} fehlt. Mit --hosts erzeugen oder Datei anlegen "
                         f"(siehe members.example.txt).")
    members: dict[int, tuple[str, int]] = {}
    for ln in path.read_text(encoding="utf-8").splitlines():
        s = ln.strip()
        if not s or s.startswith("#"):
            continue
        f = s.split()
        members[int(f[0])] = (f[1], int(f[2]))
    if not members:
        raise SystemExit(f"Fehler: {path} enthaelt keine Knoten.")
    return [members[r] for r in range(len(members))]


def node_args(rank: int, n: int, args: argparse.Namespace) -> list[str]:
    """Positionsargumente fuer RingNode (basePort=0, da members die Adressierung bestimmen)."""
    return [str(rank), str(n), "0", args.mc_addr, str(args.mc_port), str(args.p0), str(args.k),
            str(args.idle_timeout_ms), str(args.ttl),
            "true" if args.verbose_nodes else "false", str(args.startup_timeout_ms)]


def print_commands(members: list[tuple[str, int]], members_path: Path, args: argparse.Namespace) -> None:
    n = len(members)
    print("\n=== Membership ===")
    for rank, (host, port) in enumerate(members):
        role = "Initiator/PC" if rank == 0 else "Knoten"
        print(f"  rank {rank}  {host}:{port}   ({role})")
    print(f"\nmembers.txt: {members_path}")
    print(f"Broadcast-Modus: {args.bcast}   Control-Port-Basis: {args.mc_port}")

    print("\n=== Startbefehle (pro Rechner ein Prozess) ===")
    for rank in range(n):
        a = " ".join(node_args(rank, n, args))
        print(f"\n# --- rank {rank} ({members[rank][0]}) ---")
        if rank == 0:
            # PC: PowerShell, JAR-Pfad mit Leerzeichen -> in Anfuehrungszeichen.
            print("# PowerShell (PC):")
            print(f'$env:RING_MEMBERS="{members_path}"; $env:RING_BCAST="{args.bcast}"; '
                  f'java -jar "{args.jar}" {a}')
        else:
            # Handy/Termux bzw. anderer Rechner: bash, members.txt + JAR im aktuellen Verzeichnis.
            print("# bash / Termux (members.txt + JAR im aktuellen Verzeichnis):")
            print(f"RING_MEMBERS=members.txt RING_BCAST={args.bcast} java -jar {JAR_NAME} {a}")


def ensure_build(args: argparse.Namespace) -> None:
    if args.no_build:
        return
    gradlew = PROJECT_DIR / ("gradlew.bat" if sys.platform.startswith("win") else "gradlew")
    print(f"\n[build] {gradlew} jar ...", flush=True)
    subprocess.run([str(gradlew), "-p", str(PROJECT_DIR), "jar", "--console=plain"],
                   cwd=str(PROJECT_DIR), check=True)


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


def launch_local(members: list[tuple[str, int]], members_path: Path, args: argparse.Namespace) -> None:
    n = len(members)
    rank = args.local_rank
    ensure_build(args)
    jar = Path(args.jar)
    if not jar.exists():
        raise SystemExit(f"Fehler: JAR fehlt ({jar}). Erst bauen (.\\gradlew.bat jar) oder --no-build weglassen.")

    cmd = [args.java, "-jar", str(jar), *node_args(rank, n, args)]
    env = {**__import__("os").environ, "RING_MEMBERS": str(members_path), "RING_BCAST": args.bcast}
    watchdog = args.startup_timeout_ms / 1000.0 + args.timeout

    other = ", ".join(f"rank {r} auf {members[r][0]}" for r in range(n) if r != rank)
    print(f"\n[local] starte rank {rank} ({members[rank][0]}:{members[rank][1]}) ...")
    print(f"[local] JETZT die uebrigen Knoten starten: {other}")
    print(f"[local] (Initiator wartet bis zu {args.startup_timeout_ms/1000:.0f}s auf alle READY.)\n")

    proc = subprocess.Popen(cmd, cwd=str(PROJECT_DIR), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, env=env)
    try:
        out, err = proc.communicate(timeout=watchdog)
    except subprocess.TimeoutExpired:
        proc.kill()
        out, err = proc.communicate()
        print(f"[local] Watchdog ({watchdog:.0f}s) ausgeloest -> Lauf gilt als Fehlschlag.")

    summ = parse_summary(out or "")
    if summ is None:
        print("[local] kein SUMMARY empfangen.")
        tail = (err or "").strip().splitlines()[-5:]
        if tail:
            print("stderr:\n  " + "\n  ".join(tail))
        return

    print("\n=== Ergebnis ===")
    print(f"  status      : {summ.get('status')}")
    print(f"  n           : {summ.get('n')}")
    print(f"  Token-Runden: {summ.get('rounds')}")
    print(f"  Feuerwerke  : {summ.get('multicasts')}  (bcast={summ.get('bcast')}: "
          f"je Feuerwerk = {'n Unicasts' if summ.get('bcast')=='unicast' else '1 Multicast'})")
    print(f"  Rundenzeit  : min={summ.get('rt_min_ms')} mean={summ.get('rt_mean_ms')} "
          f"max={summ.get('rt_max_ms')} ms")

    out_csv = Path(args.out_dir) / "results_distributed.csv"
    fields = ["n", "status", "rounds", "multicasts", "rt_min_ms", "rt_mean_ms", "rt_max_ms", "bcast"]
    with out_csv.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerow({k: summ.get(k, "") for k in fields})
    print(f"\n==> CSV: {out_csv}")


def main() -> int:
    args = parse_args()
    members_path = Path(args.members)

    if args.hosts:
        members = parse_hosts(args.hosts, args.port)
        write_members(members_path, members)
        print(f"[config] members.txt geschrieben: {members_path}")
    else:
        members = read_members(members_path)

    print_commands(members, members_path, args)

    if args.launch_local:
        launch_local(members, members_path, args)
    else:
        print("\n(Tipp: --launch-local startet Knoten 0 hier und sammelt die Statistik automatisch.)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
