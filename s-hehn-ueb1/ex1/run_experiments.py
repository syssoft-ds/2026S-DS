import subprocess
import time

# Experimente mit wachsendem n
ns = [2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048]

results = []


def roher_output():
    for i, output in enumerate(outputs):
        print(f"  [Prozess {i} Output:]")
        print(output[-500:])


for n in ns:
    print(f"\n--- Experiment n={n} ---")

    subprocess.run(["pkill", "-f", "ring_process.py"], stderr=subprocess.DEVNULL)
    time.sleep(1)  # kurz warten, bis Ports freigegeben sind

    prozesse = []

    p0 = subprocess.Popen(
        ["python3", "-u", "ring_process.py", "0", str(n)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )
    prozesse.append(p0)

    # Prozesse 1..n-1 starten, stdout/stderr abfangen
    for i in range(1, n):
        p = subprocess.Popen(
            ["python3", "-u", "ring_process.py", str(i), str(n)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        prozesse.append(p)

    # Auf alle warten und Output einsammeln
    outputs = []

    timed_out = False
    for p in prozesse:
        try:
            stdout, _ = p.communicate(timeout=30)
            outputs.append(stdout.decode())
        except subprocess.TimeoutExpired:
            print(f"  TIMEOUT bei n={n}")
            timed_out = True
            for proc in prozesse:
                proc.kill()
            break

    if timed_out:
        results.append({"n": n, "rounds": 0, "fires": 0, "min_ms": 0, "avg_ms": 0, "max_ms": 0})
        continue
    # roher_output()


    # STATS-Zeilen parsen
    total_fires = 0
    total_rounds = 0
    all_round_times = []

    for output in outputs:
        for line in output.splitlines():
            if line.startswith("STATS fires="):
                fires = int(line.split("=")[1])
                total_fires += fires
            elif line.startswith("STATS rounds="):
                parts = line.split(" ", 2)
                rounds_val = int(parts[1].split("=")[1])
                times_str = parts[2].split("=")[1]
                times = [float(x) for x in times_str.strip("[]").split(",") if x.strip()]

                if rounds_val > 0:  # nur Prozess 0 hat sinnvolle Werte
                    total_rounds = rounds_val
                    all_round_times = times[1:] if len(times) > 1 else times

    # Statistiken berechnen
    if all_round_times:
        min_rt = min(all_round_times)
        avg_rt = sum(all_round_times) / len(all_round_times)
        max_rt = max(all_round_times)
    else:
        min_rt = avg_rt = max_rt = 0

    print(f"  Token-Runden gesamt:     {total_rounds}")
    print(f"  Multicasts gesamt:       {total_fires}")
    print(f"  Rundenzeit min:          {min_rt * 1000:.3f} ms")
    print(f"  Rundenzeit mittel:       {avg_rt * 1000:.3f} ms")
    print(f"  Rundenzeit max:          {max_rt * 1000:.3f} ms")

    results.append({
        "n": n,
        "rounds": total_rounds,
        "fires": total_fires,
        "min_ms": min_rt * 1000,
        "avg_ms": avg_rt * 1000,
        "max_ms": max_rt * 1000,
    })

# Ergebnisse als CSV speichern
with open("results.csv", "w") as f:
    f.write("n,rounds,fires,min_ms,avg_ms,max_ms\n")
    for r in results:
        f.write(f"{r['n']},{r['rounds']},{r['fires']},{r['min_ms']:.3f},{r['avg_ms']:.3f},{r['max_ms']:.3f}\n")

print("\nErgebnisse gespeichert in results.csv")
