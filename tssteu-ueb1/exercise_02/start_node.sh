#!/usr/bin/env bash
# start_node.sh — Token Ring Launcher for Exercise 2 (real network)
#
# Modes:
#   ./start_node.sh peers.txt              # print ready-to-run commands for all nodes
#   ./start_node.sh peers.txt --id 0       # run node 0 on this machine
#
# peers.txt format: one ip:port per line in ring order (node 0 first).
#                   Lines starting with # are ignored.
#
# Example peers.txt:
#   192.168.1.10:50000
#   192.168.1.11:50000
#   192.168.1.12:50000

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROCESS_PY="$SCRIPT_DIR/process.py"

# Defaults (override with flags)
P=0.5
K=3
MCAST_GROUP="239.0.0.1"
MCAST_PORT=55000
MCAST_TTL=2
MCAST_IFACE=""
START_DELAY=5   # node 0 waits this long so others can bind first
TIMEOUT=120
NODE_ID=""

usage() {
    echo "Usage: $0 <peers.txt> [--id N] [--p P] [--k K]"
    echo "         [--mcast-ttl TTL] [--mcast-iface IP]"
    echo "         [--start-delay SEC] [--timeout SEC]"
    echo ""
    echo "  peers.txt    one ip:port per line, ring order, node 0 first"
    echo "  --id N       run node N here; omit to print all commands"
    exit 1
}

[[ $# -lt 1 ]] && usage

PEERS_FILE="$1"; shift

[[ ! -f "$PEERS_FILE" ]] && { echo "ERROR: file not found: $PEERS_FILE" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --id)           NODE_ID="$2";       shift 2 ;;
        --p)            P="$2";             shift 2 ;;
        --k)            K="$2";             shift 2 ;;
        --mcast-ttl)    MCAST_TTL="$2";     shift 2 ;;
        --mcast-iface)  MCAST_IFACE="$2";   shift 2 ;;
        --start-delay)  START_DELAY="$2";   shift 2 ;;
        --timeout)      TIMEOUT="$2";       shift 2 ;;
        *) echo "Unknown option: $1" >&2; usage ;;
    esac
done

# Read peers file (skip comments and blank lines) via grep — bash 3.2 safe
TMPFILE=$(mktemp)
grep -v '^[[:space:]]*#' "$PEERS_FILE" | grep -v '^[[:space:]]*$' > "$TMPFILE"
PEER_ARRAY=()
while IFS= read -r line; do
    PEER_ARRAY+=("$line")
done < "$TMPFILE"
rm -f "$TMPFILE"

N="${#PEER_ARRAY[@]}"
[[ $N -lt 2 ]] && { echo "ERROR: need at least 2 peers in $PEERS_FILE" >&2; exit 1; }

# Build comma-separated peers string
PEERS_CSV=""
for entry in "${PEER_ARRAY[@]}"; do
    PEERS_CSV="${PEERS_CSV:+$PEERS_CSV,}$entry"
done

# Common args array (shared by all nodes)
COMMON=(
    "--peers"       "$PEERS_CSV"
    "--p"           "$P"
    "--k"           "$K"
    "--mcast-group" "$MCAST_GROUP"
    "--mcast-port"  "$MCAST_PORT"
    "--mcast-ttl"   "$MCAST_TTL"
    "--timeout"     "$TIMEOUT"
)
[[ -n "$MCAST_IFACE" ]] && COMMON+=("--mcast-iface" "$MCAST_IFACE")

# ── Print mode (no --id) ──────────────────────────────────────────────────────
if [[ -z "$NODE_ID" ]]; then
    echo "Ring: n=$N peers=$PEERS_CSV"
    echo ""
    echo "Copy the matching command to each machine and run it."
    echo "Start nodes $((N-1))..1 first, then node 0 last."
    echo "────────────────────────────────────────────────────────────"
    for i in "${!PEER_ARRAY[@]}"; do
        peer="${PEER_ARRAY[$i]}"
        args=("python3" "process.py" "--id" "$i" "${COMMON[@]}")
        [[ "$i" -eq 0 ]] && args+=("--start-delay" "$START_DELAY")
        echo "  Node $i  ($peer)"
        echo "    ${args[*]}"
        echo ""
    done
    echo "────────────────────────────────────────────────────────────"
    exit 0
fi

# ── Run mode (--id N) ─────────────────────────────────────────────────────────
if [[ ! "$NODE_ID" =~ ^[0-9]+$ ]] || [[ "$NODE_ID" -ge "$N" ]]; then
    echo "ERROR: --id $NODE_ID out of range (0...$((N-1)))" >&2
    exit 1
fi

CMD=("python3" "$PROCESS_PY" "--id" "$NODE_ID" "${COMMON[@]}")
[[ "$NODE_ID" -eq 0 ]] && CMD+=("--start-delay" "$START_DELAY")

echo "[Node $NODE_ID / ${PEER_ARRAY[$NODE_ID]}] Starting ring with n=$N"
echo "Command: ${CMD[*]}"
echo ""
exec "${CMD[@]}"
