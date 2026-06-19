#!/usr/bin/env python3
import csv, math, os, statistics, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt



K = 3                                    # Konstante für Modell der Runden bis Terminierung: log2(n)+K
path = sys.argv[1] if len(sys.argv) > 1 else "results/results.csv"
if not os.path.exists(path):
    sys.exit(f"FEHLER: '{path}' nicht gefunden.\n"
             f"Aufruf:  python plots.py <pfad/zur/results.csv>\n"
             f"(Standard ist results/results.csv -- bei --results-dir "
             f"results_gross entsprechend results_gross/results.csv)")

# Erfolgreiche Läufe einlesen und pro n mitteln
per_n = {}
with open(path) as f:
    for row in csv.DictReader(f):
        if row["ok"] != "True":
            continue
        per_n.setdefault(int(row["n"]), []).append(
            {k: float(row[k]) for k in
             ("rounds", "multicasts", "t_min_ms", "t_mean_ms", "t_max_ms")})

ns = sorted(per_n)
agg = {key: [statistics.fmean(r[key] for r in per_n[n]) for n in ns]
       for key in ("rounds", "multicasts", "t_min_ms", "t_mean_ms", "t_max_ms")}

fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(15, 4.5))

# Rundenzeiten (log-log)
ax1.loglog(ns, agg["t_max_ms"], "^-", label="max (erste Runden, Volllast)")
ax1.loglog(ns, agg["t_mean_ms"], "o-", label="mittel")
ax1.loglog(ns, agg["t_min_ms"], "v-", label="min (stille Runden)")
ax1.set_xlabel("Ringgroesse n"); ax1.set_ylabel("Rundenzeit [ms]")
ax1.set_title("Rundenzeit vs. n"); ax1.grid(True, which="both", alpha=0.3)
ax1.legend()

# Multicasts + Modell 2n
ax2.loglog(ns, agg["multicasts"], "o-", label="gemessen")
ax2.loglog(ns, [2 * n for n in ns], "k--", label="Modell: 2n")
ax2.set_xlabel("Ringgroesse n"); ax2.set_ylabel("gesendete Multicasts")
ax2.set_title("Multicasts vs. n"); ax2.grid(True, which="both", alpha=0.3)
ax2.legend()

# Runden + Modell log2(n)+k
ax3.semilogx(ns, agg["rounds"], "o-", label="gemessen")
ax3.semilogx(ns, [math.log2(n) + K for n in ns], "k--",
             label=f"Modell: log2(n)+{K}")
ax3.set_xlabel("Ringgroesse n"); ax3.set_ylabel("Token-Runden")
ax3.set_title("Runden bis Terminierung"); ax3.grid(True, which="both", alpha=0.3)
ax3.legend()


fig.tight_layout()
out = os.path.abspath("aufgabe1_plots.png")
fig.savefig(out, dpi=200)
print(f"Geschrieben: {out}")
print(f"Datenpunkte: n = {ns}")
if sys.platform == "win32":                 
    os.startfile(out)