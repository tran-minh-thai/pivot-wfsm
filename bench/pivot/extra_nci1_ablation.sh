#!/usr/bin/env bash
# ==============================================================================
# extra_nci1_ablation.sh - Re-measure the NCI1 ablation with all five seeds.
#
#   bash bench/pivot/extra_nci1_ablation.sh
#
# Why this exists
# ---------------
# The ablation used to run NCI1 on two seeds while every other configuration used
# five, so the NCI1 row reported a standard deviation over n=2 next to rows with
# n=5. Two points do not give a meaningful spread. The exception has been removed
# from reproduce.*, and the cost turns out to be about a minute, so there was
# never a reason for it.
#
# This script fills that one gap without re-running the whole suite: it measures
# ONLY the NCI1 2x2 ablation at 10%/0.5 over all five seeds, with the same
# protocol, JVM flags and CSV columns as reproduce.sh.
#
# Output
# ------
# results/pivot/extra/ablation_nci1.csv - same columns as the ablation.csv that
# reproduce.sh writes. Nothing under results/pivot/*.csv is touched.
#
# Requirements: JDK 21 + Maven, and data/weighted/ already generated. Runs
# offline. Takes roughly two minutes.
#
# This is the macOS/Linux twin of extra_nci1_ablation.ps1; keep the two in step.
# ==============================================================================
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="results/pivot/extra"; mkdir -p "$OUT"

TAG="NCI1"
SIG="0.10"
TAU="0.5"
SEEDS="42 1337 2024 31415 271828"
VARIANTS="pivot pivot-nopf pivot-plain pivot-plain-nopf"

echo ">> Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package >/dev/null
DEPS="$(mvn -o -q -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime dependency:build-classpath | tr -d '[:space:]')"
[ -d target/classes ] && [ -n "$DEPS" ] || { echo "Offline build failed - run prepare.sh first (while online)."; exit 1; }
export CLASSPATH="target/classes:$DEPS"

[ -f "data/weighted/per_instance/normal/$TAG.normal.s42.json" ] || {
  echo "No data yet. Run (while ONLINE): bash bench/pivot/prepare.sh"; exit 1; }

mean_sd(){ # reads numbers on stdin
  awk '{v[n++]=$1; s+=$1} END{ if(n==0){print "n/a"; exit}
        m=s/n; for(i=0;i<n;i++) d+=(v[i]-m)^2; sd=(n>1)?sqrt(d/(n-1)):0;
        printf "%.0f +/- %.0f (n=%d)", m, sd, n }'
}

echo ">> NCI1 ablation 2x2, sigma=10%, tauW=0.5, five seeds ..."
ACSV="$OUT/ablation_nci1.csv"; echo "dataset,sigma,tau,variant,seed,mean_ms" > "$ACSV"
for v in $VARIANTS; do
  : > "/tmp/.nci1_abl_$v"
  for s in $SEEDS; do
    f="data/weighted/per_instance/normal/$TAG.normal.s$s.json"
    line="$(java -Xmx4g pivotwfsm.cli.SingleRun "$v" "$f" "$SIG" "$TAU" 2 5 2>/dev/null | grep "^$v," | head -1 || true)"
    if [ -n "$line" ]; then
      ms="$(echo "$line" | cut -d, -f7)"
      echo "$TAG,$SIG,$TAU,$v,$s,$ms" >> "$ACSV"
      echo "$ms" >> "/tmp/.nci1_abl_$v"
      printf "   %-18s seed %-7s %s ms\n" "$v" "$s" "$ms"
    else
      echo "   $v seed $s FAILED"
    fi
  done
done

echo
echo "==== NCI1 ablation, sigma=10%, tauW=0.5, five seeds ========================"
for v in $VARIANTS; do
  printf "  %-18s: %s ms\n" "$v" "$(mean_sd < "/tmp/.nci1_abl_$v")"
done
awk 'NR==FNR{p+=$1;pn++;next}{q+=$1;qn++} END{ if(pn&&qn&&p>0)
      printf "  pivot anchoring (plain/pivot, with pf) : %.2fx\n", (q/qn)/(p/pn) }' \
    /tmp/.nci1_abl_pivot /tmp/.nci1_abl_pivot-plain
echo "============================================================================"
rm -f /tmp/.nci1_abl_*
echo
echo "CSV written to $ACSV"
