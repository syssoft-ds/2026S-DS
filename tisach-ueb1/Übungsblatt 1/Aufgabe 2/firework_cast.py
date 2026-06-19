#!/usr/bin/env python3

import argparse, json, os, random, socket, struct, sys, threading, time

RETRANS = 0.3        # Wartezeit bis Wiederholung einer Token-Uebergabe [s]
                     # (groesser als in Aufg.1: echtes Netz hat hoehere Latenz)
MAX_RETRANS = 50     # danach gilt der Nachfolger als ausgefallen
STOP_SENDS = 5       # STOP mehrfach senden 
GRP, MPORT = "239.10.10.10", 49999

ap = argparse.ArgumentParser()
ap.add_argument("--config", required=True, help="ring.json (auf allen gleich)")
ap.add_argument("--id", type=int, required=True, help="eigene Position 0..n-1")
ap.add_argument("--bcast", choices=["multicast", "unicast"], default="multicast")
ap.add_argument("--p0", type=float, default=1.0)
ap.add_argument("--k", type=int, default=3)
ap.add_argument("--outdir", default="stats")
ap.add_argument("--startup-delay", type=float, default=3.0)
ap.add_argument("--idle", type=float, default=60.0)
A = ap.parse_args()

ring = json.load(open(A.config))                 # [{"ip","port"}, ...]
N = len(ring)
me, succ = ring[A.id], ring[(A.id + 1) % N]
others = [r for j, r in enumerate(ring) if j != A.id]


# Token-Socket: an die EIGENE IP binden (nicht localhost!), Port aus ring.json
tok = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
tok.bind(("0.0.0.0", me["port"]))                # 0.0.0.0 = auf allen Interfaces
tok.settimeout(0.05)
nxt = (succ["ip"], succ["port"])

def usend(data, addr):
    try:
        tok.sendto(data, addr)
    except OSError:
        pass                                     # Windows-ICMP-Reset tolerieren

# Multicast-Socket
mc = None
if A.bcast == "multicast":
    mc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    mc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        mc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    mc.bind(("", MPORT))
    mc.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                  struct.pack("4s4s", socket.inet_aton(GRP),
                              socket.inet_aton("0.0.0.0")))
    mc.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2) 
    mc.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)

def broadcast(msg):
    """Rakete/STOP an alle senden -- je nach Modus."""
    data = json.dumps(msg).encode()
    if A.bcast == "multicast":
        mc.sendto(data, (GRP, MPORT))
    else:                                        # n-1 Unicasts ueber den Ring-Socket
        for r in others:
            usend(data, (r["ip"], r["port"]))

# Zustand & Statistik
p = A.p0
last_tid = 0
pending = None
quiet, round_times, t_round = 0, [], None
stop, stop_regular = threading.Event(), threading.Event()
S = {"id": A.id, "tokens_handled": 0, "rockets_sent": 0,
     "rockets_received": 0, "retransmits": 0}

def handle_bcast(m):
    """Empfangene Rakete/STOP verarbeiten (aus beiden Empfangswegen)."""
    if m["t"] == "STOP":
        stop_regular.set(); stop.set()
    elif m["t"] == "ROCKET":
        S["rockets_received"] += 1

def mc_listener():                               # nur im Multicast-Modus
    while True:
        try:
            m = json.loads(mc.recv(4096))
        except ConnectionResetError:
            continue
        handle_bcast(m)
        if stop.is_set():
            return

if A.bcast == "multicast":
    threading.Thread(target=mc_listener, daemon=True).start()

def hold_token(m):
    global p, pending
    S["tokens_handled"] += 1
    if random.random() < p:                     
        S["rockets_sent"] += 1
        m["fired"] = True
        broadcast({"t": "ROCKET", "von": A.id})
    p /= 2.0
    m["tid"] += 1
    data = json.dumps(m).encode()
    pending = [data, m["tid"], time.monotonic() + RETRANS, 0]
    usend(data, nxt)

# Main Loop: Auf Token warten, bis STOP oder Zeitlimit
random.seed()
failed = False
if A.id == 0:
    time.sleep(A.startup_delay)                  # Startphase aller Prozesse abwarten
    t_round = time.monotonic()
    hold_token({"t": "TOKEN", "fired": False, "tid": 0})

idle = time.monotonic()
while not stop.is_set():
    if pending and time.monotonic() > pending[2]:
        pending[3] += 1
        if pending[3] > MAX_RETRANS:
            failed = True; break
        S["retransmits"] += 1
        pending[2] = time.monotonic() + RETRANS
        usend(pending[0], nxt)
    try:
        data, sender = tok.recvfrom(4096)
    except ConnectionResetError:
        continue
    except socket.timeout:
        if time.monotonic() - idle > A.idle: break
        continue
    idle = time.monotonic()
    m = json.loads(data)
    t = m["t"]
    if t == "ACK":
        if pending and m["tid"] == pending[1]:
            pending = None
    elif t in ("ROCKET", "STOP"):                # nur im Unicast-Modus hier
        handle_bcast(m)
    elif t == "TOKEN":
        usend(json.dumps({"t": "ACK", "tid": m["tid"]}).encode(), sender)
        if m["tid"] <= last_tid: continue        # Retransmit-Duplikat
        last_tid = m["tid"]
        if A.id == 0:
            now = time.monotonic()
            round_times.append(now - t_round); t_round = now
            quiet = 0 if m["fired"] else quiet + 1
            if quiet >= A.k:
                for _ in range(STOP_SENDS):
                    broadcast({"t": "STOP"})
                    time.sleep(0.02)
                break
            m["fired"] = False
        hold_token(m)

# Statistik schreiben 
if A.id == 0:
    S["rounds"], S["round_times"] = len(round_times), round_times
S["bcast"] = A.bcast
S["host"] = socket.gethostname()
os.makedirs(A.outdir, exist_ok=True)
with open(os.path.join(A.outdir, f"node_{A.id:05d}.json"), "w") as f:
    json.dump(S, f)
print(f"[P{A.id}@{S['host']}] beendet: tokens={S['tokens_handled']} "
      f"rockets_sent={S['rockets_sent']} rockets_recv={S['rockets_received']} "
      f"retransmits={S['retransmits']}"
      + (f" rounds={S['rounds']}" if A.id == 0 else ""))
sys.exit(0 if (stop_regular.is_set() or (A.id == 0 and not failed)) else 1)