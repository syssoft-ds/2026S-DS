#!/usr/bin/env python3
import json, sys

DEFAULT_PORT = 50000

if len(sys.argv) < 3:
    sys.exit("Mindestens 2 IP-Adressen angeben.\n"
             "Beispiel: python make_ring.py 192.168.1.10 192.168.1.11 192.168.1.12")

ring = []
for arg in sys.argv[1:]:
    if ":" in arg:
        ip, port = arg.split(":")
        ring.append({"ip": ip, "port": int(port)})
    else:
        ring.append({"ip": arg, "port": DEFAULT_PORT})

with open("ring.json", "w") as f:
    json.dump(ring, f, indent=2)

print(f"ring.json mit {len(ring)} Teilnehmern geschrieben:")
for i, r in enumerate(ring):
    print(f"  P{i}: {r['ip']}:{r['port']}")
print("\nDiese Datei auf ALLE Rechner kopieren (gleicher Inhalt ueberall).")