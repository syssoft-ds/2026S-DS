import socket
import sys
import time
import random

BASE_PORT = 6000
K = 3
DEBUG = False

# Aufruf: python3 ring_process_aufgabe2.py <my_id> <n> <peers_file>
#
# peers_file: eine Zeile pro Prozess, IP-Adresse, Zeilennummer (0-basiert) = Prozess-ID
# Beispiel fuer n=2 (Host + VM):
#   127.0.0.1
#   192.168.64.5

my_id = int(sys.argv[1])
n = int(sys.argv[2])
peers_file = sys.argv[3]

with open(peers_file) as f:
    peer_ips = [line.strip() for line in f if line.strip()]

assert len(peer_ips) == n, f"peers_file enthaelt {len(peer_ips)} Zeilen, erwartet n={n}"

my_ip = peer_ips[my_id]
my_port = BASE_PORT + my_id

next_id = (my_id + 1) % n
next_addr = (peer_ips[next_id], BASE_PORT + next_id)

coordinator_addr = (peer_ips[0], BASE_PORT + 0)

# Nur noch EIN Socket: Token, STOP und Feuerwerk laufen alle ueber Unicast
# auf demselben Port -> kein zweiter (Multicast-)Socket, kein select() mehr noetig.
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", my_port))

if DEBUG:
    print(f"[Prozess {my_id}] {my_ip}:{my_port}, Nachfolger {next_addr}")

p = 0.5
no_fire_counter = 0
fire_count = 0
received_fireworks = 0
round_times = []
last_send_time = None


def broadcast_firework():
    """Unicast-Fallback: Feuerwerk-Nachricht einzeln an alle anderen Prozesse senden."""
    msg = f"FIRE:{my_id}".encode()
    for i in range(n):
        if i == my_id:
            continue
        sock.sendto(msg, (peer_ips[i], BASE_PORT + i))


# READY-Logik: identisch zu ring_process.py, nur mit echten Adressen
if my_id != 0:
    sock.sendto(b"READY", coordinator_addr)
else:
    ready_count = 0
    while ready_count < n - 1:
        data, addr = sock.recvfrom(1024)
        if data == b"READY":
            ready_count += 1
            if DEBUG:
                print(f"[Prozess 0] {ready_count}/{n - 1} Prozesse bereit")
    last_send_time = time.time()
    sock.sendto(b"TOKEN:0", next_addr)

while True:
    data, addr = sock.recvfrom(1024)
    msg = data.decode()

    if msg.startswith("FIRE:"):
        received_fireworks += 1
        if DEBUG:
            sender_id = msg.split(":")[1]
            print(f"[Prozess {my_id}] Feuerwerk empfangen von Prozess {sender_id}")
        continue

    if msg == "STOP":
        if DEBUG:
            print(f"[Prozess {my_id}] STOP erhalten, beende mich.")
        if my_id != 0:
            sock.sendto(b"STOP", next_addr)

        print(f"STATS fires={fire_count}")
        print(f"STATS received={received_fireworks}")
        if DEBUG:
            print(f"STATS rounds={len(round_times)} round_times={round_times}")
        sys.exit(0)

    # TOKEN:<flag>
    _, flag_str = msg.split(":")
    fire_in_last_round = (flag_str == "1")
    if DEBUG:
        print(f"[Prozess {my_id}] Token erhalten, aktuelles p={p:.4f}")

    if my_id == 0:
        if fire_in_last_round:
            no_fire_counter = 0
        else:
            no_fire_counter += 1

        if no_fire_counter == K:
            if DEBUG:
                print(f"[Prozess {my_id}] {K} Runden ohne Zuendung -> STOP")
            sock.sendto(b"STOP", next_addr)

            print(f"STATS fires={fire_count}")
            print(f"STATS received={received_fireworks}")
            print(f"STATS rounds={len(round_times)} round_times={round_times}")
            sys.exit(0)

    fire_now = False
    if random.random() < p:
        if DEBUG:
            print(f"[Prozess {my_id}] ZUeNDET! Sende Feuerwerk an alle (Unicast)")
        fire_now = True
        fire_count += 1
        broadcast_firework()

    p = p / 2

    if my_id == 0:
        now = time.time()
        round_times.append(now - last_send_time)
        last_send_time = now
        new_flag = "1" if fire_now else "0"
    else:
        new_flag = "1" if (fire_in_last_round or fire_now) else "0"

    sock.sendto(f"TOKEN:{new_flag}".encode(), next_addr)