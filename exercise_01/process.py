"""
Distributed Systems Exercise 1 – Token Ring with UDP Fireworks

Single process in a logical ring. Token circulates via UDP unicast.
Fireworks are broadcast via UDP multicast.
Process 0 manages round evaluation and termination.
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
    s.bind(('127.0.0.1', port))
    return s


def create_mcast_send_sock() -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    # Explicitly route multicast via loopback on macOS
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF,
                 socket.inet_aton('127.0.0.1'))
    return s


def create_mcast_recv_sock(mcast_group: str, mcast_port: int) -> socket.socket:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except AttributeError:
        pass
    s.bind(('', mcast_port))
    mreq = struct.pack('4sL', socket.inet_aton(mcast_group), socket.INADDR_ANY)
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    s.setblocking(False)
    return s


def udp_send(data: bytes, host: str, port: int) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.sendto(data, (host, port))


def main() -> None:
    parser = argparse.ArgumentParser(description='Token ring process')
    parser.add_argument('--id', type=int, required=True, help='Process ID (0..n-1)')
    parser.add_argument('--n', type=int, required=True, help='Total number of processes')
    parser.add_argument('--p', type=float, default=0.5, help='Initial fire probability')
    parser.add_argument('--k', type=int, default=3, help='Consecutive empty rounds to terminate')
    parser.add_argument('--base-port', type=int, default=50000, help='Base UDP port for ring')
    parser.add_argument('--mcast-group', type=str, default='239.0.0.1', help='Multicast group address')
    parser.add_argument('--mcast-port', type=int, default=55000, help='Multicast port')
    parser.add_argument('--start-delay', type=float, default=0.0,
                        help='Seconds to wait before sending initial token (process 0 only)')
    parser.add_argument('--timeout', type=float, default=120.0,
                        help='Socket receive timeout in seconds')
    args = parser.parse_args()

    proc_id = args.id
    n = args.n
    p = args.p
    k = args.k
    base_port = args.base_port
    mcast_group = args.mcast_group
    mcast_port = args.mcast_port

    my_port = base_port + proc_id
    next_port = base_port + (proc_id + 1) % n

    ring_sock = create_ring_recv_sock(my_port)
    ring_sock.settimeout(args.timeout)
    mcast_send_sock = create_mcast_send_sock()
    mcast_recv_sock = create_mcast_recv_sock(mcast_group, mcast_port)

    # Statistics accumulated by process 0
    round_times: list[float] = []
    total_fireworks: int = 0

    def fire_firework(round_num: int) -> None:
        msg = json.dumps({'type': 'firework', 'from': proc_id, 'round': round_num}).encode()
        mcast_send_sock.sendto(msg, (mcast_group, mcast_port))

    def drain_mcast() -> int:
        """Non-blocking drain of multicast receive buffer; returns count of messages."""
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
        udp_send(json.dumps(token).encode(), '127.0.0.1', next_port)

    try:
        while True:
            try:
                data, _ = ring_sock.recvfrom(BUFFER_SIZE)
            except socket.timeout:
                print(f'[P{proc_id}] ERROR: timeout waiting for token', file=sys.stderr)
                sys.exit(1)

            msg = json.loads(data.decode())

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
                    udp_send(json.dumps(msg).encode(), '127.0.0.1', next_port)
                break

            # --- Token handling ---
            if proc_id == 0:
                # Complete the outgoing round
                rt = time.time() - msg['round_start']
                round_times.append(rt)
                total_fireworks += msg['fireworks']

                if msg['fireworks'] == 0:
                    msg['consecutive_empty'] += 1
                else:
                    msg['consecutive_empty'] = 0

                if msg['consecutive_empty'] >= k:
                    terminate = json.dumps({'type': 'terminate'}).encode()
                    udp_send(terminate, '127.0.0.1', next_port)
                    # Wait for terminate to traverse ring and return
                    continue

                # Start next round
                msg['round'] += 1
                msg['fireworks'] = 0
                msg['round_start'] = time.time()

            # Drain any received multicast messages (fire-and-forget receipts)
            drain_mcast()

            # Fire with probability p
            if random.random() < p:
                fire_firework(msg['round'])
                msg['fireworks'] += 1
            p /= 2

            udp_send(json.dumps(msg).encode(), '127.0.0.1', next_port)

    finally:
        ring_sock.close()
        mcast_send_sock.close()
        mcast_recv_sock.close()


if __name__ == '__main__':
    main()
