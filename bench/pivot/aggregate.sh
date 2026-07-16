#!/usr/bin/env bash
# aggregate.sh <csv_dir> - print mean+-sd tables from the raw CSV of reproduce.sh.
set -u
SRC="${1:-results/pivot/repro}"
# CRLF-safe: CSV generated on Windows may end lines with \r\n; make a copy with
# \r stripped so every awk (including the $9=="ok" filter) runs correctly on mac/Linux.
D=$(mktemp -d)
for f in "$SRC"/*.csv; do [ -f "$f" ] && tr -d '\r' < "$f" > "$D/$(basename "$f")"; done
msd(){ awk -F, '{s[$1]+=$2; ss[$1]+=$2*$2; n[$1]++}
  END{for(k in s){m=s[k]/n[k]; v=(n[k]>1)?(ss[k]-s[k]*s[k]/n[k])/(n[k]-1):0;
      printf "  %-34s %7.1f +/- %5.1f  (n=%d)\n", k, m, (v>0?sqrt(v):0), n[k]}}' | sort; }

echo; echo "==== TABLE 1: Peak memory (peak heap MB, mean+-sd) ===="
[ -f "$D/mem.csv" ] && awk -F, 'NR>1 && $9=="ok"{print $1"("$2")/"$3" s="$4" t="$5","$7}' "$D/mem.csv" | msd

echo; echo "==== OOM (NCI1 5%/0.3 @2g) ===="
[ -f "$D/oom.csv" ] && { awk -F, 'NR>1 && $5=="ok"{print $1","$3}' "$D/oom.csv" | msd
  awk -F, 'NR>1{c[$1"/"$5]++} END{for(k in c) printf "  status %-20s %d times\n",k,c[k]}' "$D/oom.csv"; }

echo; echo "==== TABLE 2: Warm timing (ms, mean+-sd) ===="
[ -f "$D/time.csv" ] && awk -F, 'NR>1{print $1" s="$2" t="$3"/"$4","$6}' "$D/time.csv" | msd

echo; echo "==== TABLE 3: Phase breakdown (matching %) ===="
[ -f "$D/phases.csv" ] && awk -F, 'NR>1{printf "  %-26s total=%7.1f  matching=%7.1f (%s%%)\n",$1" s="$2" t="$3,$5,$10,$11}' "$D/phases.csv"

echo; echo "==== TABLE 4: MUTAG sensitivity (memory reduction & speedup) ===="
[ -f "$D/sensitivity.csv" ] && awk -F, 'NR>1{printf "  s=%-5s t=%s  #patterns=%-5s  mem_reduction=%sx  speedup=%sx\n",$1,$2,$3,$6,$9}' "$D/sensitivity.csv"

echo; echo "==== TABLE 5: Ablation (ms, mean+-sd) ===="
[ -f "$D/ablation.csv" ] && awk -F, 'NR>1{print $1" s="$2" t="$3" ["$4"]"","$6}' "$D/ablation.csv" | msd

echo; echo "==== 5.8 Published baselines (MUTAG static): peak heap ===="
[ -f "$D/baselines.csv" ] && awk -F, 'NR>1{printf "  %-13s %-16s peak=%sMB  #patterns=%s\n",$1,$2,$3,$4}' "$D/baselines.csv"

echo; echo "==== Pruning stats (pivot, seed 42): candidates/prefiltered/nonCanonical/evaluated ===="
[ -f "$D/prune.csv" ] && awk -F, 'NR>1 && $5==42{printf "  %-22s cand=%s pref=%s nonCanon=%s eval=%s\n",$1"("$2") s="$3" t="$4,$6,$7,$8,$9}' "$D/prune.csv"
