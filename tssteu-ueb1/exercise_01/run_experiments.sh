#!/usr/bin/env bash
# Runs token ring experiments for increasing n and prints a results table.
# Requires process.py in the same directory.

P=${1:-0.5}   # initial fire probability
K=${2:-3}     # consecutive empty rounds to terminate

BASE_PORT=50000
MCAST_PORT=55000
EXPERIMENT_TIMEOUT=300

NS=(2 4 8 16 32 64)

TMPDIR_RESULTS=$(mktemp -d)
MAX_N=0

print_header() {
    printf "\n%-6s  %-7s  %-11s  %-12s  %-13s  %-11s\n" \
        "n" "rounds" "multicasts" "min_rt(ms)" "mean_rt(ms)" "max_rt(ms)"
    printf '%0.s-' {1..68}
    echo
}

run_experiment() {
    local N=$1
    local STARTUP_DELAY
    local SOCK_TIMEOUT
    STARTUP_DELAY=$(echo "$N * 0.02 + 0.5" | bc -l)
    SOCK_TIMEOUT=$(echo "$STARTUP_DELAY + $N * 0.01 + 60" | bc -l)

    local OUTFILE="$TMPDIR_RESULTS/out_n${N}.txt"
    local PIDS=()

    # Start processes n-1 down to 1
    for (( I=N-1; I>=1; I-- )); do
        python3 process.py \
            --id "$I" \
            --n "$N" \
            --p "$P" \
            --k "$K" \
            --base-port "$BASE_PORT" \
            --mcast-port "$MCAST_PORT" \
            --timeout "$SOCK_TIMEOUT" \
            >> "$OUTFILE" 2>&1 &
        PIDS+=($!)
        sleep 0.02
    done

    # Process 0 last
    python3 process.py \
        --id 0 \
        --n "$N" \
        --p "$P" \
        --k "$K" \
        --base-port "$BASE_PORT" \
        --mcast-port "$MCAST_PORT" \
        --timeout "$SOCK_TIMEOUT" \
        --start-delay "$STARTUP_DELAY" \
        >> "$OUTFILE" 2>&1 &
    PIDS+=($!)

    # Wait with timeout
    local DEADLINE=$(( $(date +%s) + EXPERIMENT_TIMEOUT ))
    for PID in "${PIDS[@]}"; do
        local REMAINING=$(( DEADLINE - $(date +%s) ))
        if (( REMAINING <= 0 )); then
            kill "$PID" 2>/dev/null
        else
            # Wait up to remaining seconds
            ( sleep "$REMAINING"; kill "$PID" 2>/dev/null ) &
            local KILLER=$!
            wait "$PID" 2>/dev/null
            kill "$KILLER" 2>/dev/null
            wait "$KILLER" 2>/dev/null
        fi
    done

    # Parse STATS line from output
    grep "^STATS" "$OUTFILE"
}

parse_stats() {
    local LINE=$1
    # STATS n=4 rounds=7 multicasts=3 min_rt=0.000320 mean_rt=0.000632 max_rt=0.001655
    echo "$LINE" | awk '{
        for (i=1; i<=NF; i++) {
            split($i, kv, "=")
            val[kv[1]] = kv[2]
        }
        printf "%-6s  %-7s  %-11s  %12.3f  %13.3f  %11.3f\n",
            val["n"], val["rounds"], val["multicasts"],
            val["min_rt"]*1000, val["mean_rt"]*1000, val["max_rt"]*1000
    }'
}

# ── Main ──────────────────────────────────────────────────────────────────────

echo "Token Ring UDP Experiments  (p=$P, k=$K)"
echo "Running n = ${NS[*]}"

RESULTS=()

for N in "${NS[@]}"; do
    printf "  n=%-4s  running..." "$N"
    T_START=$(date +%s%3N)

    STATS_LINE=$(run_experiment "$N")

    T_END=$(date +%s%3N)
    ELAPSED=$(echo "scale=1; ($T_END - $T_START) / 1000" | bc)

    if [[ -n "$STATS_LINE" ]]; then
        MAX_N=$N
        ROUNDS=$(echo "$STATS_LINE" | grep -o 'rounds=[0-9]*' | cut -d= -f2)
        MCASTS=$(echo "$STATS_LINE" | grep -o 'multicasts=[0-9]*' | cut -d= -f2)
        printf "  OK  (%.1fs, %s rounds, %s multicasts)\n" "$ELAPSED" "$ROUNDS" "$MCASTS"
        RESULTS+=("$STATS_LINE")
    else
        printf "  FAILED / TIMEOUT after %.1fs\n" "$ELAPSED"
        break
    fi

    sleep 0.5
done

echo ""
echo "Maximum successful n: $MAX_N"

print_header
for LINE in "${RESULTS[@]}"; do
    parse_stats "$LINE"
done
printf '%0.s-' {1..68}
echo ""

# Cleanup
rm -rf "$TMPDIR_RESULTS"
