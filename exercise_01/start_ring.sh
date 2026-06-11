#!/usr/bin/env bash
# Usage: ./start_ring.sh <n> [p] [k]
# Starts n processes in a token ring on localhost.
# Process 0 is started last and initiates the token after all others are ready.

N=${1:?Usage: $0 <n> [p] [k]}
P=${2:-0.5}
K=${3:-3}

BASE_PORT=50000
MCAST_PORT=55000
STARTUP_DELAY=$(echo "$N * 0.02 + 0.5" | bc -l)
SOCK_TIMEOUT=$(echo "$STARTUP_DELAY + $N * 0.01 + 60" | bc -l)

echo "Starting token ring: n=$N  p=$P  k=$K"
echo "  startup_delay=${STARTUP_DELAY}s  sock_timeout=${SOCK_TIMEOUT}s"

PIDS=()

# Start processes n-1 down to 1 first
for (( I=N-1; I>=1; I-- )); do
    python3 process.py \
        --id "$I" \
        --n "$N" \
        --p "$P" \
        --k "$K" \
        --base-port "$BASE_PORT" \
        --mcast-port "$MCAST_PORT" \
        --timeout "$SOCK_TIMEOUT" &
    PIDS+=($!)
    sleep 0.02
done

# Process 0 last with startup delay so others are ready
python3 process.py \
    --id 0 \
    --n "$N" \
    --p "$P" \
    --k "$K" \
    --base-port "$BASE_PORT" \
    --mcast-port "$MCAST_PORT" \
    --timeout "$SOCK_TIMEOUT" \
    --start-delay "$STARTUP_DELAY"
PIDS+=($!)

# Wait for all background processes
for PID in "${PIDS[@]}"; do
    wait "$PID"
done

echo "Ring n=$N finished."
