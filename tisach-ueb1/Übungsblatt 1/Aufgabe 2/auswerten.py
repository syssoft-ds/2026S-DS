#!/usr/bin/env python3
import glob, json, os, statistics, sys

d = sys.argv[1] if len(sys.argv) > 1 else "stats"
files = sorted(glob.glob(os.path.join(d, "node_*.json")))
if not files:
    sys.exit(f"Keine node_*.json in '{d}'. Erst die Dateien aller Rechner "
             f"hierher kopieren.")

nodes = [json.load(open(f)) for f in files]
p0 = next(s for s in nodes if s["id"] == 0)
n = len(nodes)
bcast = p0.get("bcast", "?")
times = p0.get("round_times", [])
rockets = sum(s["rockets_sent"] for s in nodes)
received = sum(s["rockets_received"] for s in nodes)

# Erwartete empfangene Raketen je nach Broadcast-Modus:
#   multicast: jeder empfängt jede Rakete inkl. eigener (Loopback) -> rockets*n
#   unicast:   Sender empfängt eigene nicht                       -> rockets*(n-1)
expected = rockets * (n if bcast == "multicast" else n - 1)
delivery = 100 * received / expected if expected else 100.0

print(f"=== Aufgabe 2: verteilter Ring, n={n}, Broadcast={bcast} ===")
print(f"Beteiligte Hosts: {', '.join(s.get('host','?') for s in nodes)}")
print(f"Token-Runden gesamt:        {p0.get('rounds', 0)}")
print(f"Gesendete Raketen gesamt:   {rockets}")
print(f"STOP-Multicasts:            {1}  (mehrfach gesendet, idempotent)")
print(f"Retransmits gesamt:         {sum(s['retransmits'] for s in nodes)}")
print(f"Raketen-Zustellrate:        {delivery:.1f}%  "
      f"({received}/{expected} erwartet)")
if times:
    ms = [t * 1e3 for t in times]
    print(f"Rundenzeit min/mittel/max:  "
          f"{min(ms):.2f} / {statistics.fmean(ms):.2f} / {max(ms):.2f} ms")
print("\nPro Rechner:")
for s in sorted(nodes, key=lambda x: x["id"]):
    print(f"  P{s['id']}@{s.get('host','?'):<12} "
          f"tokens={s['tokens_handled']:>3} sent={s['rockets_sent']:>3} "
          f"recv={s['rockets_received']:>3} rtx={s['retransmits']:>3}")