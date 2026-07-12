"""
Aufgabe 2 - Ein Feuerwerk an UDP-Nachrichten (Verteilt)

Wie Aufgabe 1, aber verteilt: auf jedem Rechner laeuft genau EIN Prozess des
logischen Rings. Die Ring-Topologie steht in einer Peers-Datei (eine Zeile
"host:port" pro Prozess, Zeilenreihenfolge = Ringreihenfolge). Alle Rechner
verwenden dieselbe Peers-Datei; jeder Prozess wird mit --id <Zeilenindex>
gestartet.

Feuerwerke (Broadcasts) werden nach Moeglichkeit auf UDP-Multicast abgebildet
(Gruppe 239.1.2.3, funktioniert i.d.R. nur im selben Subnetz/LAN). Wo Multicast
nicht moeglich ist (verschiedene Netze, WLAN-AP-Isolation, Eduroam, ...),
schaltet --unicast-fireworks auf n-1 Unicast-Nachrichten an alle anderen
Prozesse um (diese kommen dann auf dem Token-Socket an).

Start: erst alle Prozesse 1..n-1 starten, P0 zuletzt (P0 startet das Token
nach --start-delay Sekunden). Logik (lokales p, "fired"-Flag im Token,
k stille Runden, STOP sammelt Statistik ein) ist identisch zu Aufgabe 1.

Beispiel (3 Rechner, peers.txt auf allen identisch):
    peers.txt:  192.168.1.10:20000
                192.168.1.11:20000
                192.168.1.12:20000
    Rechner .11:  python aufgabe_2.py --id 1
    Rechner .12:  python aufgabe_2.py --id 2
    Rechner .10:  python aufgabe_2.py --id 0      (startet das Token)

Hinweis Windows-Firewall: eingehendes UDP auf dem Token-Port (und ggf. dem
Multicast-Port) erlauben, z.B.:
    New-NetFirewallRule -DisplayName "DS Ring" -Direction Inbound `
        -Protocol UDP -LocalPort 20000,22999 -Action Allow
"""

from random import random
import socket
import struct
import json
import argparse
import statistics
import sys
import time


def load_peers(path):
    """Peers-Datei lesen: eine Zeile "host:port" pro Prozess, # = Kommentar."""
    peers = []
    with open(path) as f:
        for line in f:
            line = line.split("#")[0].strip()
            if not line:
                continue
            host, port = line.rsplit(":", 1)
            peers.append((host, int(port)))
    return peers


def create_token_socket(my_port, timeout, quiet, process_id):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", my_port))
    sock.settimeout(timeout)
    if not quiet:
        print(f"P{process_id}: Token-Socket auf Port {my_port}")
    return sock


def create_multicast_socket(group, port, ttl, quiet):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", port))

    mreq = struct.pack("4sL", socket.inet_aton(group), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, ttl)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)

    sock.setblocking(False)
    if not quiet:
        print(f"Multicast-Gruppe {group}:{port} beigetreten (TTL={ttl})")
    return sock


def send_json(sock, msg, addr):
    sock.sendto(json.dumps(msg).encode(), addr)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", type=int, required=True, help="eigener Index in der Peers-Datei")
    parser.add_argument("--peers", default="peers.txt", help="Datei mit host:port pro Zeile")
    parser.add_argument("--p", type=float, default=0.5)
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--multicast-group", default="239.1.2.3")
    parser.add_argument("--multicast-port", type=int, default=22999)
    parser.add_argument("--ttl", type=int, default=1, help="Multicast-TTL (1 = eigenes Subnetz)")
    parser.add_argument("--unicast-fireworks", action="store_true",
                        help="Feuerwerke als n-1 Unicasts statt Multicast senden")
    parser.add_argument("--start-delay", type=float, default=3.0,
                        help="Wartezeit von P0 vor dem ersten Token")
    parser.add_argument("--timeout", type=float, default=60.0,
                        help="Sekunden ohne Nachricht bis zum Abbruch")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    peers = load_peers(args.peers)
    n = len(peers)
    process_id = args.id
    k = args.k
    quiet = args.quiet
    if not 0 <= process_id < n:
        sys.exit(f"--id {process_id} passt nicht zur Peers-Datei (n={n})")

    my_port = peers[process_id][1]
    successor = peers[(process_id + 1) % n]

    local_p = args.p
    fireworks_sent = 0          # gezuendete Feuerwerke dieses Prozesses
    fireworks_seen = 0
    round_times = []            # nur P0
    silent_rounds = 0           # nur P0
    current_round = 0
    t_round_start = None

    token_socket = create_token_socket(my_port, args.timeout, quiet, process_id)
    multicast_socket = None
    if not args.unicast_fireworks:
        multicast_socket = create_multicast_socket(
            args.multicast_group, args.multicast_port, args.ttl, quiet)

    if not quiet:
        print(f"P{process_id}: Ring mit n={n}, Nachfolger {successor[0]}:{successor[1]}, "
              f"Feuerwerk per {'Unicast (n-1)' if args.unicast_fireworks else 'Multicast'}")

    def fire_maybe(rnd):
        """Zuendentscheidung fuer Runde rnd; halbiert danach das lokale p."""
        nonlocal fireworks_sent, local_p
        fired = random() < local_p
        if fired:
            fireworks_sent += 1
            msg = {"type": "FIREWORK", "sender": process_id, "round": rnd}
            if args.unicast_fireworks:
                for j, peer in enumerate(peers):
                    if j != process_id:
                        send_json(token_socket, msg, peer)
            else:
                send_json(multicast_socket, msg,
                          (args.multicast_group, args.multicast_port))
            if not quiet:
                print(f"P{process_id} ZUENDET (Runde {rnd}, p war {local_p:.4f})")
        local_p /= 2
        return fired

    def drain_multicast():
        """Eingetroffene Multicast-Feuerwerke lesen (nur Anzeige/Statistik)."""
        nonlocal fireworks_seen
        if multicast_socket is None:
            return
        while True:
            try:
                data, _ = multicast_socket.recvfrom(4096)
            except BlockingIOError:
                break
            except ConnectionResetError:
                continue
            msg = json.loads(data.decode())
            if msg.get("type") == "FIREWORK" and msg["sender"] != process_id:
                fireworks_seen += 1
                if not quiet:
                    print(f"P{process_id} sieht FEUERWERK von P{msg['sender']} "
                          f"(Runde {msg['round']})")

    # P0 startet die erste Runde, nachdem alle anderen Prozesse laufen
    if process_id == 0:
        if not quiet:
            print(f"P0 wartet {args.start_delay}s, dann startet das Token...")
        time.sleep(args.start_delay)
        fired = fire_maybe(0)
        t_round_start = time.perf_counter()
        send_json(token_socket, {"type": "TOKEN", "round": 0, "fired": fired}, successor)

    # ----------------------------
    # EMPFANGSSCHLEIFE
    # ----------------------------
    while True:
        try:
            data, _ = token_socket.recvfrom(4096)
        except socket.timeout:
            print(f"P{process_id}: TIMEOUT - Token verloren? Abbruch.", file=sys.stderr)
            sys.exit(1)
        except ConnectionResetError:
            continue
        msg = json.loads(data.decode())

        drain_multicast()

        if msg["type"] == "FIREWORK":
            # Unicast-Modus: Feuerwerke kommen auf dem Token-Socket an
            fireworks_seen += 1
            if not quiet:
                print(f"P{process_id} sieht FEUERWERK von P{msg['sender']} "
                      f"(Runde {msg['round']})")

        elif msg["type"] == "TOKEN":

            if process_id == 0:
                # Token ist einmal herum -> Runde abgeschlossen
                round_times.append(time.perf_counter() - t_round_start)

                if msg["fired"]:
                    silent_rounds = 0
                else:
                    silent_rounds += 1

                if not quiet:
                    print(f"===== Runde {current_round} fertig "
                          f"({round_times[-1]*1000:.2f} ms, "
                          f"{'Feuerwerk' if msg['fired'] else 'still'}, "
                          f"stille Runden: {silent_rounds}/{k}) =====")

                if silent_rounds >= k:
                    send_json(token_socket,
                              {"type": "STOP", "total_multicasts": fireworks_sent},
                              successor)
                    continue  # auf Ruecklauf von STOP warten

                current_round += 1
                fired = fire_maybe(current_round)
                t_round_start = time.perf_counter()
                send_json(token_socket,
                          {"type": "TOKEN", "round": current_round, "fired": fired},
                          successor)

            else:
                fired = fire_maybe(msg["round"])
                send_json(token_socket,
                          {"type": "TOKEN", "round": msg["round"],
                           "fired": msg["fired"] or fired},
                          successor)

        elif msg["type"] == "STOP":

            if process_id == 0:
                drain_multicast()
                summary = {
                    "type": "SUMMARY",
                    "n": n,
                    "k": k,
                    "p_start": args.p,
                    "fireworks": "unicast" if args.unicast_fireworks else "multicast",
                    "rounds": len(round_times),
                    "total_multicasts": msg["total_multicasts"],
                    "round_time_min_ms": min(round_times) * 1000,
                    "round_time_avg_ms": statistics.mean(round_times) * 1000,
                    "round_time_max_ms": max(round_times) * 1000,
                }
                print("SUMMARY " + json.dumps(summary))
                break

            else:
                msg["total_multicasts"] += fireworks_sent
                send_json(token_socket, msg, successor)
                print(f"P{process_id} fertig "
                      f"(gezuendet: {fireworks_sent}, gesehen: {fireworks_seen})")
                break

    token_socket.close()
    if multicast_socket is not None:
        multicast_socket.close()


if __name__ == "__main__":
    main()
