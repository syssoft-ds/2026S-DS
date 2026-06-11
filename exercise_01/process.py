#!/usr/bin/env python3
"""Firework ring — one process per node in the logical UDP ring."""

import argparse
import socket
import struct
import sys

MCAST_GROUP_DEFAULT = "239.0.0.1"
MCAST_PORT_DEFAULT  = 5007
BASE_PORT_DEFAULT   = 6000


def parse_args():
    ap = argparse.ArgumentParser(description="Firework ring node")
    ap.add_argument("id",           type=int,   help="Process ID (0 .. n-1)")
    ap.add_argument("n",            type=int,   help="Total number of processes")
    ap.add_argument("--p",          type=float, default=0.8,               dest="prob",
                    help="Initial fire probability (default: 0.8)")
    ap.add_argument("--k",          type=int,   default=3,
                    help="Consecutive quiet rounds before termination (default: 3)")
    ap.add_argument("--base-port",  type=int,   default=BASE_PORT_DEFAULT,
                    help=f"Base port for token ring (default: {BASE_PORT_DEFAULT})")
    ap.add_argument("--mcast-group",            default=MCAST_GROUP_DEFAULT,
                    help=f"Multicast group (default: {MCAST_GROUP_DEFAULT})")
    ap.add_argument("--mcast-port", type=int,   default=MCAST_PORT_DEFAULT,
                    help=f"Multicast port (default: {MCAST_PORT_DEFAULT})")
    return ap.parse_args()


def make_unicast_socket(process_id: int, base_port: int) -> socket.socket:
    """Receive token from predecessor — each process gets its own port."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", base_port + process_id))
    return sock


def make_multicast_socket(mcast_group: str, mcast_port: int) -> socket.socket:
    """Send and receive FIREWORK / STOP messages via UDP multicast."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):        # macOS needs this for multi-process bind
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL,  1)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)  # receive own multicasts
    sock.bind(("", mcast_port))
    mreq = struct.pack("4sL", socket.inet_aton(mcast_group), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    return sock


def main():
    args = parse_args()

    sock_uni   = make_unicast_socket(args.id, args.base_port)
    sock_mcast = make_multicast_socket(args.mcast_group, args.mcast_port)

    print(f"[{args.id}] ready  port={args.base_port + args.id}  "
          f"n={args.n}  p={args.prob}  k={args.k}", flush=True)

    sock_uni.close()
    sock_mcast.close()


if __name__ == "__main__":
    main()
