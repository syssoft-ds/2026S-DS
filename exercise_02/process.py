"""
Distributed Systems Exercise 2 – Token Ring with UDP Fireworks (Real Network)

One process per real machine. Ring topology via explicit peer list.
Token circulates via UDP unicast. Fireworks via UDP multicast.
Process 0 manages round evaluation and termination.

Usage:
    python process.py --id 0 --peers 192.168.1.10:50000,192.168.1.11:50000,192.168.1.12:50000

Each entry in --peers corresponds to a node in ring order.
--id must match the index of THIS machine's ip:port in the list.
"""

import socket
import json
import time
import sys
import argparse
import struct
import random

BUFFER_SIZE = 65536


def create_ring_recv_sock(port: int) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('0.0.0.0', port))
    return s


def create_mcast_send_sock(ttl: int, iface: str = '') -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, ttl)
    if iface:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface))
    return s


def create_mcast_recv_sock(mcast_group: str, mcast_port: int, iface: str = '') -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except AttributeError:
        pass
    s.bind(('', mcast_port))
    local_if = socket.inet_aton(iface) if iface else socket.inet_aton('0.0.0.0')
    mreq = struct.pack('4s4s', socket.inet_aton(mcast_group), local_if)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.setblocking(False)
    return s


def udp_send(data: bytes, host: str, port: int) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.sendto(data, (host, port))


def parse_peers(peers_str: str) -> list[tuple[str, int]]:
    result = []
    for entry in peers_str.split(','):
        entry = entry.strip()
        ip, port_str = entry.rsplit(':', 1)
        result.append((ip, int(port_str)))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description='Token ring process (distributed, real network)')
    parser.add_argument('--id', type=int, required=True,
                        help='This node\'s index in --peers (0..n-1)')
    parser.add_argument('--peers', type=str, required=True,
                        help='Comma-separated ip:port list of all nodes in ring order, '
                             'e.g. 192.168.1.10:50000,192.168.1.11:50000')
    parser.add_argument('--p', type=float, default=0.5, help='Initial fire probability')
    parser.add_argument('--k', type=int, default=3, help='Consecutive empty rounds to terminate')
    parser.add_argument('--mcast-group', type=str, default='239.0.0.1', help='Multicast group address')
    parser.add_argument('--mcast-port', type=int, default=55000, help='Multicast port')
    parser.add_argument('--mcast-ttl', type=int, default=2,
                        help='Multicast TTL (1=localhost only, 2+=LAN)')
    parser.add_argument('--mcast-iface', type=str, default='',
                        help='Local IP of the network interface for multicast (optional)')
    parser.add_argument('--no-multicast', action='store_true',
                        help='Disable multicast; send fireworks as n-1 unicasts to ring ports')
    parser.add_argument('--start-delay', type=float, default=0.0,
                        help='Seconds to wait before sending initial token (process 0 only)')
    parser.add_argument('--timeout', type=float, default=120.0,
                        help='Socket receive timeout in seconds')
    args = parser.parse_args()

    peers = parse_peers(args.peers)
    n = len(peers)
    proc_id = args.id
    p = args.p
    k = args.k

    if proc_id < 0 or proc_id >= n:
        print(f'ERROR: --id {proc_id} out of range for {n} peers', file=sys.stderr)
        sys.exit(1)

    my_ip, my_port = peers[proc_id]
    next_ip, next_port = peers[(proc_id + 1) % n]

    ring_sock = create_ring_recv_sock(my_port)
    ring_sock.settimeout(args.timeout)

    if not args.no_multicast:
        # Use local peer IP as multicast interface if not explicitly given.
        mcast_iface = args.mcast_iface if args.mcast_iface else my_ip
        mcast_send_sock = create_mcast_send_sock(args.mcast_ttl, mcast_iface)
        mcast_recv_sock = create_mcast_recv_sock(args.mcast_group, args.mcast_port, mcast_iface)
    else:
        mcast_send_sock = None
        mcast_recv_sock = None

    round_times: list[float] = []
    total_fireworks: int = 0

    def fire_firework(round_num: int) -> None:
        payload = json.dumps({'type': 'firework', 'from': proc_id, 'round': round_num}).encode()
        if args.no_multicast:
            for i, (ip, port) in enumerate(peers):
                if i != proc_id:
                    udp_send(payload, ip, port)
        else:
            mcast_send_sock.sendto(payload, (args.mcast_group, args.mcast_port))

    def drain_mcast() -> int:
        if mcast_recv_sock is None:
            return 0
        count = 0
        try:
            while True:
                mcast_recv_sock.recvfrom(BUFFER_SIZE)
                count += 1
        except BlockingIOError:
            pass
        return count

    if proc_id == 0:
        if args.start_delay > 0:
            time.sleep(args.start_delay)

        token = {
            'type': 'token',
            'round': 0,
            'fireworks': 0,
            'consecutive_empty': 0,
        }
        if random.random() < p:
            fire_firework(0)
            token['fireworks'] += 1
        p /= 2
        token['round_start'] = time.time()
        udp_send(json.dumps(token).encode(), next_ip, next_port)

    try:
        while True:
            try:
                data, _ = ring_sock.recvfrom(BUFFER_SIZE)
            except socket.timeout:
                print(f'[P{proc_id}] ERROR: timeout waiting for token', file=sys.stderr)
                sys.exit(1)

            msg = json.loads(data.decode())

            if msg['type'] == 'firework':
                continue  # unicast broadcast received — discard, count lives in token

            if msg['type'] == 'terminate':
                if proc_id == 0:
                    n_rounds = len(round_times)
                    min_rt = min(round_times) if round_times else 0.0
                    mean_rt = sum(round_times) / n_rounds if n_rounds else 0.0
                    max_rt = max(round_times) if round_times else 0.0
                    print(
                        f'STATS n={n} rounds={n_rounds} multicasts={total_fireworks} '
                        f'min_rt={min_rt:.6f} mean_rt={mean_rt:.6f} max_rt={max_rt:.6f}'
                    )
                    sys.stdout.flush()
                else:
                    udp_send(json.dumps(msg).encode(), next_ip, next_port)
                break

            # --- Token handling ---
            if proc_id == 0:
                rt = time.time() - msg['round_start']
                round_times.append(rt)
                total_fireworks += msg['fireworks']

                if msg['fireworks'] == 0:
                    msg['consecutive_empty'] += 1
                else:
                    msg['consecutive_empty'] = 0

                if msg['consecutive_empty'] >= k:
                    terminate = json.dumps({'type': 'terminate'}).encode()
                    udp_send(terminate, next_ip, next_port)
                    continue

                msg['round'] += 1
                msg['fireworks'] = 0
                msg['round_start'] = time.time()

            drain_mcast()

            if random.random() < p:
                fire_firework(msg['round'])
                msg['fireworks'] += 1
            p /= 2

            udp_send(json.dumps(msg).encode(), next_ip, next_port)

    finally:
        ring_sock.close()
        if mcast_send_sock:
            mcast_send_sock.close()
        if mcast_recv_sock:
            mcast_recv_sock.close()


if __name__ == '__main__':
    main()
