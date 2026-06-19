import socket
import sys
import time
import random
import struct
import select

BASE_PORT = 6000
MCAST_GRP = "224.1.1.1"
MCAST_PORT = 5007
DEBUG = False

K = 3

my_id = int(sys.argv[1])
n = int(sys.argv[2])

my_port = BASE_PORT + my_id
next_port = BASE_PORT + (my_id + 1) % n

# UDP Socket für das Token
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", my_port))

# UDP Socket für Multicast
mcast_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
mcast_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
mcast_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
mcast_sock.bind(("", MCAST_PORT))

mreq = struct.pack("4sl", socket.inet_aton(MCAST_GRP), socket.INADDR_ANY)
mcast_sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

if DEBUG:
    print(f"[Prozess {my_id}] läuft auf Port {my_port}, Nachfolger ist Port {next_port}")

p = 0.5
no_fire_counter = 0
fire_count = 0
round_times = []
last_send_time = None

# READY-Logik: erst NACH mcast_sock setup
if my_id != 0:
    # Bereit-Signal an Prozess 0 (beide Sockets sind jetzt bereit)
    sock.sendto(b"READY", ("127.0.0.1", BASE_PORT))
else:
    # Prozess 0 wartet bis alle bereit sind
    ready_count = 0
    while ready_count < n - 1:
        data, addr = sock.recvfrom(1024)
        if data == b"READY":
            ready_count += 1
            if DEBUG:
                print(f"[Prozess 0] {ready_count}/{n - 1} Prozesse bereit")
    # Jetzt erst Token schicken
    last_send_time = time.time()
    sock.sendto(b"TOKEN:0", ("127.0.0.1", next_port))

while True:
    readable, _, _ = select.select([sock, mcast_sock], [], [])

    for s in readable:
        if s is sock:
            data, addr = sock.recvfrom(1024)
            msg = data.decode()

            if msg == 'STOP':
                if DEBUG:
                    print(f"[Prozess {my_id}] STOP erhalten, beende mich.")
                if my_id != 0:
                    sock.sendto(b"STOP", ("127.0.0.1", next_port))

                # WICHTIG: fires immer drucken (auch ohne DEBUG),
                # damit run_experiments.py ueber ALLE n Prozesse summieren kann.
                print(f"STATS fires={fire_count}")
                if DEBUG:
                    print(f"STATS rounds={len(round_times)} round_times={round_times}")
                sys.exit(0)

            _, flag_str = msg.split(':')
            fire_in_last_round = (flag_str == '1')
            if DEBUG:
                print(f"[Prozess {my_id}] habe Token erhalten, aktuelles p={p:.4f}")

            if my_id == 0:
                if fire_in_last_round:
                    no_fire_counter = 0
                else:
                    no_fire_counter += 1

                if no_fire_counter == K:
                    if DEBUG:
                        print(f"[Prozess {my_id}] {K} Runden ohne Zündung -> STOP")
                    sock.sendto(b"STOP", ("127.0.0.1", next_port))

                    print(f"STATS fires={fire_count}")
                    print(f"STATS rounds={len(round_times)} round_times={round_times}")
                    sys.exit(0)

            fire_now = False
            if random.random() < p:
                if DEBUG:
                    print(f"[Prozess {my_id}] ZÜNDET! Sende Feuerwerk-Multicast")
                fire_now = True
                fire_count += 1
                mcast_sock.sendto(f"Feuerwerk von Prozess {my_id}!".encode(), (MCAST_GRP, MCAST_PORT))

            p = p / 2

            if my_id == 0:
                now = time.time()
                round_times.append(now - last_send_time)
                last_send_time = now
                new_flag = '1' if fire_now else '0'
            else:
                new_flag = '1' if (fire_in_last_round or fire_now) else '0'

            sock.sendto(f"TOKEN:{new_flag}".encode(), ("127.0.0.1", next_port))

        mcast_sock.setblocking(False)
        while True:
            try:
                data, addr = mcast_sock.recvfrom(1024)
            except BlockingIOError:
                break
            if DEBUG:
                print(f"[Prozess {my_id}] Feuerwerk empfangen: {data.decode()}")