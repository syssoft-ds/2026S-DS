#!/usr/bin/env python3
import argparse, json, os, random, socket, struct, sys, threading, time

LO, BASE, GRP, MPORT = "127.0.0.1", 50000, "239.10.10.10", 49999
RETRANS = 0.2        # Wartezeit, bis eine unbestätigte Übergabe wiederholt wird [s]
MAX_RETRANS = 50     # danach gilt der Nachfolger als ausgefallen
# Idle-Notausstieg kommt als Parameter (--idle): er muss die Startphase
# überdauern (Prozess-Spawn von n Prozessen + Startverzoegerung von P0).
STOP_SENDS = 3       # STOP mehrfach senden (zuverlässigere Erkennung, dass die Runde wirklich vorbei ist und dass alle Prozesse das mitbekommen haben)

ap = argparse.ArgumentParser()
ap.add_argument("--id", type=int, required=True)
ap.add_argument("--n", type=int, required=True)
ap.add_argument("--p0", type=float, default=1.0)
ap.add_argument("--k", type=int, default=3)
ap.add_argument("--outdir", default="stats")
ap.add_argument("--startup-delay", type=float, default=1.0)
ap.add_argument("--idle", type=float, default=30.0)
A = ap.parse_args()

# Sockets: Unicast-Ring + Multicast-Gruppe 
WIN = sys.platform == "win32"
# Hinweis Windows: ICMP "Port unreachable" erscheint dort als
# ConnectionResetError auf UDP-Sockets (z.B. beim Startrennen, wenn an einen
# noch ungebundenen Port gesendet wurde). Das wird an allen Sende- und
# Empfangsstellen defensiv abgefangen.

tok = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
tok.bind((LO, BASE + A.id))
tok.settimeout(0.05)                            # Takt fuer Retransmit-Pruefung
nxt = (LO, BASE + (A.id + 1) % A.n)             # Ringtopologie = Port-Arithmetik

def usend(data, addr):
    """Unicast-Senden, das Windows-ICMP-Resets toleriert (Retransmit
    gleicht einen verworfenen Versuch ohnehin aus)."""
    try:
        tok.sendto(data, addr)
    except OSError:
        pass

mc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
mc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
if hasattr(socket, "SO_REUSEPORT"):             # n Prozesse teilen einen Port
    mc.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
mc.bind(("", MPORT))
# Windows kann kein Multicast über das Loopback-Interface: dort läuft die
# Gruppe über das Standard-Interface (0.0.0.0); IP_MULTICAST_LOOP sorgt
# dafür, dass die Pakete trotzdem alle lokalen Mitglieder erreichen
mc_if = "0.0.0.0" if WIN else LO
mc.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
              struct.pack("4s4s", socket.inet_aton(GRP), socket.inet_aton(mc_if)))
if not WIN:
    mc.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(LO))
mc.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)

#Zustand & Statistik
p = A.p0
last_tid = 0           # höchste akzeptierte Transfer-ID (Duplikat-Filter)
pending = None         # unbestätigte Übergabe: [daten, tid, frist, versuche]
quiet, round_times, t_round = 0, [], None
stop, stop_regular = threading.Event(), threading.Event()
S = {"id": A.id, "tokens_handled": 0, "rockets_sent": 0,
     "rockets_received": 0, "retransmits": 0}

def listener():        # Multicast-Thread: Raketen zählen, STOP erkennen
    while True:
        try:
            m = json.loads(mc.recv(4096))
        except ConnectionResetError:
            continue                            # Windows-ICMP-Echo ignorieren
        if m["t"] == "STOP":
            stop_regular.set(); stop.set(); return
        S["rockets_received"] += 1              # inkl. eigener (Multicast-Loop)

threading.Thread(target=listener, daemon=True).start()

# Hilfsfunktion: Token halten, Rakete zünden (je nach p) und weitergeben
def hold_token(m):
    global p, pending
    S["tokens_handled"] += 1
    if random.random() < p:                  
        S["rockets_sent"] += 1
        m["fired"] = True
        mc.sendto(json.dumps({"t": "ROCKET", "von": A.id}).encode(), (GRP, MPORT))
    p /= 2.0                                    # p = p/2 pro Durchlauf
    m["tid"] += 1                               # Transfer-ID wächst pro Hop
    data = json.dumps(m).encode()
    pending = [data, m["tid"], time.monotonic() + RETRANS, 0]
    usend(data, nxt)

# Hauptschleife
random.seed()
failed = False
if A.id == 0:                                   # P0 erzeugt das EINZIGE Token
    time.sleep(A.startup_delay)                 # alle müssen gebunden haben
    t_round = time.monotonic()
    hold_token({"t": "TOKEN", "fired": False, "tid": 0})

idle = time.monotonic()
while not stop.is_set():
    if pending and time.monotonic() > pending[2]:        # Übergabe wiederholen
        pending[3] += 1
        if pending[3] > MAX_RETRANS:
            failed = True; break                         # Nachfolger tot
        S["retransmits"] += 1
        pending[2] = time.monotonic() + RETRANS
        usend(pending[0], nxt)
    try:
        data, sender = tok.recvfrom(4096)
    except ConnectionResetError:
        continue                                # Windows-ICMP-Echo ignorieren
    except socket.timeout:
        if time.monotonic() - idle > A.idle: break       # Notausstieg
        continue
    idle = time.monotonic()
    m = json.loads(data)
    if m["t"] == "ACK":
        if pending and m["tid"] == pending[1]:
            pending = None                               # Übergabe bestätigt
    elif m["t"] == "TOKEN":
        # Empfang IMMER bestaetigen, auch Duplikate: das vorige ACK
        # kann selbst verloren gegangen sein.
        usend(json.dumps({"t": "ACK", "tid": m["tid"]}).encode(), sender)
        if m["tid"] <= last_tid: continue                # Retransmit-Duplikat
        last_tid = m["tid"]
        if A.id == 0:                                    # Runde abgeschlossen
            now = time.monotonic()
            round_times.append(now - t_round); t_round = now
            quiet = 0 if m["fired"] else quiet + 1
            if quiet >= A.k:                             # k stille Runden
                for _ in range(STOP_SENDS):
                    mc.sendto(json.dumps({"t": "STOP"}).encode(), (GRP, MPORT))
                break                                    # Token absorbiert
            m["fired"] = False
        hold_token(m)

#  Statistik schreiben; Exit-Code = reguläre Terminierung? 
if A.id == 0:
    S["rounds"], S["round_times"] = len(round_times), round_times
os.makedirs(A.outdir, exist_ok=True)
with open(os.path.join(A.outdir, f"node_{A.id:05d}.json"), "w") as f:
    json.dump(S, f)
sys.exit(0 if (stop_regular.is_set() or (A.id == 0 and not failed)) else 1)