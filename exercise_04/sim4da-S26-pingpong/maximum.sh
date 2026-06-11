for n in 10 50 100 500 1000 2000 5000; do
  echo "Starte Experiment für n=$n..."
  ./gradlew runTokenRing --args="$n 0.5 3" --quiet
done