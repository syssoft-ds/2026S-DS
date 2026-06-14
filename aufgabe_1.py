"""
Aufgabe 1 - Ein Feuerwerk an UDP-Nachrichten (Pseudo-Verteilt)

n Prozesse bilden einen logischen Ring auf localhost (127.0.0.1). Ein Token
kreist im Ring (UDP-Unicast an den Nachfolger). Wer das Token erhaelt, zuendet
mit Wahrscheinlichkeit p eine Feuerwerksrakete (UDP-Multicast an alle Prozesse)
und halbiert anschliessend SEINE lokale Zuendwahrscheinlichkeit (p = p/2 pro
Durchlauf, wie im Aufgabenblatt gefordert). Die Anwendung terminiert, wenn in
k aufeinanderfolgenden Runden kein Prozess gezuendet hat.

Konsistenz der Terminierungsentscheidung:
Ob in einer Runde gezuendet wurde, entscheidet P0 NICHT anhand der asynchron
eintreffenden Multicasts (die koennten zum Entscheidungszeitpunkt noch
unterwegs sein), sondern anhand eines "fired"-Flags, das im Token selbst
mitreist und von jedem zuendenden Prozess gesetzt wird. Damit ist die
Entscheidung deterministisch und unabhaengig von Multicast-Laufzeiten.

Statistik:
P0 misst die Rundenzeiten (Token einmal komplett im Ring herum). Beim
Terminieren schickt P0 eine STOP-Nachricht durch den Ring; jeder Prozess
addiert die Anzahl seiner gesendeten Multicasts auf einen Zaehler in der
STOP-Nachricht. Kommt STOP zu P0 zurueck, gibt P0 eine SUMMARY-Zeile aus
(und schickt sie optional per UDP an das Experiment-Script).
"""

from random import random
import socket
import struct
import json
import argparse
import statistics
import sys
import time


MULTICAST_GROUP = "224.1.1.1"
MULTICAST_PORT = 6000
LOCALHOST = "127.0.0.1"


# ----------------------------
# SOCKETS
# ----------------------------

def create_token_socket(process_id, base_port, quiet):
    port = base_port + process_id

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((LOCALHOST, port))

    if not quiet:
        print(f"P{process_id}: Token-Socket auf Port {port}")
    return sock


def create_multicast_socket(quiet):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", MULTICAST_PORT))

    mreq = struct.pack("4sL", socket.inet_aton(MULTICAST_GROUP), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

    # Lokale Zustellung der eigenen Multicasts (alle Prozesse auf einem Host)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)

    sock.setblocking(False)
    if not quiet:
        print("Multicast-Gruppe beigetreten")
    return sock


# ----------------------------
# SENDEN / EMPFANGEN
# ----------------------------

def send_json(sock, msg, addr):
    sock.sendto(json.dumps(msg).encode(), addr)


def drain_multicast(sock, process_id, quiet):
    """Alle bisher eingetroffenen Multicasts lesen, gesehene Feuerwerke zaehlen."""
    seen = 0
    while True:
        try:
            data, _ = sock.recvfrom(4096)
        except BlockingIOError:
            break
        except ConnectionResetError:
            # Windows: ICMP "port unreachable" eines frueheren Sendevorgangs
            continue
        msg = json.loads(data.decode())
        if msg.get("type") == "FIREWORK":
            seen += 1
            if not quiet:
                print(f"P{process_id} sieht FEUERWERK von P{msg['sender']} (Runde {msg['round']})")
    return seen


# ----------------------------
# MAIN
# ----------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", type=int, required=True)
    parser.add_argument("--n", type=int, required=True)
    parser.add_argument("--p", type=float, required=True)
    parser.add_argument("--k", type=int, default=3)
    # Basis-Port 20000: der Bereich um 5000 ist unter Windows teilweise von
    # Systemdiensten belegt (z.B. UDP 5050) -> Bind schlaegt mit WinError 10013 fehl
    parser.add_argument("--base-port", type=int, default=20000)
    parser.add_argument("--runner-port", type=int, default=None,
                        help="UDP-Port des Experiment-Scripts (READY/GO/SUMMARY-Handshake)")
    parser.add_argument("--timeout", type=float, default=30.0,
                        help="Sekunden ohne Nachricht bis zum Abbruch (muss bei "
                             "grossem n die Startphase aller Prozesse abdecken)")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    process_id = args.id
    n = args.n
    k = args.k
    quiet = args.quiet

    local_p = args.p            # lokale Zuendwahrscheinlichkeit dieses Prozesses
    fireworks_sent = 0          # eigene gesendete Multicasts
    fireworks_seen = 0          # beobachtete Feuerwerke (nur Anzeige/Statistik)
    round_times = []            # nur P0: Dauer jeder Runde in Sekunden
    silent_rounds = 0           # nur P0: Runden in Folge ohne Feuerwerk
    current_round = 0
    t_round_start = None

    token_socket = create_token_socket(process_id, args.base_port, quiet)
    multicast_socket = create_multicast_socket(quiet)
    token_socket.settimeout(args.timeout)

    successor_port = args.base_port + (process_id + 1) % n
    runner_addr = (LOCALHOST, args.runner_port) if args.runner_port else None

    if not quiet:
        print(f"P{process_id}: Nachfolger P{(process_id + 1) % n} auf Port {successor_port}")

    # Dem Experiment-Script melden, dass die Sockets gebunden sind
    if runner_addr:
        send_json(token_socket, {"type": "READY", "id": process_id}, runner_addr)

    def fire_maybe(rnd):
        """Zuendentscheidung dieses Prozesses fuer Runde rnd; halbiert danach p."""
        nonlocal fireworks_sent, local_p
        fired = random() < local_p
        if fired:
            fireworks_sent += 1
            send_json(multicast_socket,
                      {"type": "FIREWORK", "sender": process_id, "round": rnd},
                      (MULTICAST_GROUP, MULTICAST_PORT))
            if not quiet:
                print(f"P{process_id} ZUENDET (Runde {rnd}, p war {local_p:.4f})")
        local_p /= 2
        return fired

    # P0 startet die erste Runde - entweder nach GO vom Experiment-Script
    # oder (manueller Start) nach kurzer Wartezeit, bis alle gebunden haben.
    if process_id == 0:
        if runner_addr:
            while True:
                data, _ = token_socket.recvfrom(4096)
                if json.loads(data.decode()).get("type") == "GO":
                    break
        else:
            time.sleep(1.5)

        fired = fire_maybe(0)
        t_round_start = time.perf_counter()
        send_json(token_socket,
                  {"type": "TOKEN", "round": 0, "fired": fired},
                  (LOCALHOST, successor_port))

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

        fireworks_seen += drain_multicast(multicast_socket, process_id, quiet)

        if msg["type"] == "TOKEN":

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
                    # Terminierung: STOP durch den Ring schicken und die
                    # Multicast-Zaehler aller Prozesse einsammeln
                    send_json(token_socket,
                              {"type": "STOP", "total_multicasts": fireworks_sent},
                              (LOCALHOST, successor_port))
                    continue  # auf Ruecklauf von STOP warten

                # Naechste Runde starten (P0 zuendet selbst zuerst)
                current_round += 1
                fired = fire_maybe(current_round)
                t_round_start = time.perf_counter()
                send_json(token_socket,
                          {"type": "TOKEN", "round": current_round, "fired": fired},
                          (LOCALHOST, successor_port))

            else:
                # Eigene Zuendentscheidung, Flag im Token akkumulieren
                fired = fire_maybe(msg["round"])
                send_json(token_socket,
                          {"type": "TOKEN", "round": msg["round"],
                           "fired": msg["fired"] or fired},
                          (LOCALHOST, successor_port))

        elif msg["type"] == "STOP":

            if process_id == 0:
                # STOP ist einmal herum -> Gesamtstatistik ausgeben
                fireworks_seen += drain_multicast(multicast_socket, process_id, quiet)
                summary = {
                    "type": "SUMMARY",
                    "n": n,
                    "k": k,
                    "p_start": args.p,
                    "rounds": len(round_times),
                    "total_multicasts": msg["total_multicasts"],
                    "round_time_min_ms": min(round_times) * 1000,
                    "round_time_avg_ms": statistics.mean(round_times) * 1000,
                    "round_time_max_ms": max(round_times) * 1000,
                }
                print("SUMMARY " + json.dumps(summary))
                if runner_addr:
                    send_json(token_socket, summary, runner_addr)
                break

            else:
                msg["total_multicasts"] += fireworks_sent
                send_json(token_socket, msg, (LOCALHOST, successor_port))
                if not quiet:
                    print(f"P{process_id} STOP weitergeleitet "
                          f"(gesendet: {fireworks_sent}, gesehen: {fireworks_seen})")
                break

    token_socket.close()
    multicast_socket.close()


if __name__ == "__main__":
    main()
