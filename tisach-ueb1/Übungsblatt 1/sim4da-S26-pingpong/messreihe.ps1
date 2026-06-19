# Aufgabe 3 - Messreihe fuer die sim4da-Simulation.
# Startet pro n einen eigenen JVM-Lauf (saubere, unabhaengige Simulationen)
# und sammelt die CSV-Zeilen in results_sim.csv.
#
# Aufruf (im Projektordner sim4da-S26-pingpong):
#   .\messreihe.ps1                      # n=2..1024, 3 Wiederholungen
#   .\messreihe.ps1 -MaxN 4096 -Reps 5   # groesseres Maximum, mehr Wiederholungen
#   .\messreihe.ps1 -JvmArgs "-Xss256k"  # kleinere Thread-Stacks fuer grosses n

param(
    [int]$MaxN = 1024,
    [double]$P0 = 1.0,
    [int]$K = 3,
    [int]$Reps = 3,
    [string]$Out = "results_sim.csv",
    [string]$JvmArgs = ""
)

# Projekt einmal bauen, damit gradlew danach nur noch ausfuehrt
Write-Host "Baue Projekt..." -ForegroundColor Cyan
.\gradlew.bat classes -q

# CSV-Kopf schreiben
"n,run,rounds,rocketsSent,rocketsReceived,t_min_ms,t_mean_ms,t_max_ms,wall_ms" |
    Out-File -Encoding ascii $Out

$n = 2
while ($n -le $MaxN) {
    for ($run = 0; $run -lt $Reps; $run++) {
        # Optional JVM-Args fuer grosse n (Thread-Stacks/Heap)
        $argLine = "$n $P0 $K csv"
        if ($JvmArgs -ne "") {
            $line = (.\gradlew.bat run -q --args="$argLine" `
                     "-Dorg.gradle.jvmargs=$JvmArgs" 2>$null) | Select-Object -Last 1
        } else {
            $line = (.\gradlew.bat run -q --args="$argLine" 2>$null) |
                     Select-Object -Last 1
        }

        if ($line -match '^\d+,') {
            # n,rounds,sent,recv,min,mean,max,wall  ->  run einfuegen
            $parts = $line -split ','
            $row = "{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
                   $parts[0], $run, $parts[1], $parts[2], $parts[3], `
                   $parts[4], $parts[5], $parts[6], $parts[7]
            $row | Out-File -Encoding ascii -Append $Out
            Write-Host ("n={0,5} run={1} rounds={2,3} mc={3,5} recv={4,7} t={5}/{6}/{7}ms wall={8}ms" -f `
                $parts[0], $run, $parts[1], $parts[2], $parts[3], `
                $parts[4], $parts[5], $parts[6], $parts[7]) -ForegroundColor Green
        } else {
            Write-Host ("n={0} run={1}: GESCHEITERT" -f $n, $run) -ForegroundColor Red
            Write-Host "  Letzte Ausgabe: $line" -ForegroundColor DarkGray
            Write-Host "Abbruch der Messreihe bei n=$n." -ForegroundColor Yellow
            Write-Host "Maximales erfolgreiches n liegt darunter. CSV: $Out"
            exit
        }
    }
    $n *= 2
}
Write-Host "`nMessreihe komplett. Ergebnisse in $Out" -ForegroundColor Cyan