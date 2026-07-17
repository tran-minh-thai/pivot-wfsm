#!/usr/bin/env bash
# ==============================================================================
# extra_ptcmr_tau05.sh - Measure the ONE configuration the suite was missing:
# PTC-MR at tauW=0.5.
#
#   bash bench/pivot/extra_ptcmr_tau05.sh
#
# Why this exists
# ---------------
# Every normal-distribution dataset was measured at tauW=0.5 except PTC-MR, which
# was only ever measured at 0.3. The two are not interchangeable: at 0.5 PTC-MR
# admits far fewer patterns, so the embedding store stays small and the memory
# advantage narrows, while the runtime advantage widens. Reporting both makes the
# common setting present for every dataset and shows that effect instead of
# leaving it implied.
#
# reproduce.sh now includes this configuration, so a future full run picks it up.
# This script exists so you do not have to re-run the whole suite just to fill one
# gap: it measures ONLY PTC-MR at 10%/0.5, with the same protocol, the same JVM
# flags and the same CSV columns as reproduce.sh.
#
# Output
# ------
# results/pivot/extra/{mem,time,phases,ablation}.csv - same columns as the files
# reproduce.sh writes, so the rows can be read alongside the existing ones.
# Nothing under results/pivot/*.csv (the reference set) is touched.
#
# Requirements: JDK 21 + Maven, and data/weighted/ already generated (see
# bench/pivot/README_REPRO.md). Runs offline. Takes a few minutes.
#
# This is the macOS/Linux twin of extra_ptcmr_tau05.ps1; keep the two in step.
# ==============================================================================
set -euo pipefail
cd "$(dirname "$0")/../.."

OUT="results/pivot/extra"; mkdir -p "$OUT"

TAG="PTC_MR"
SIG="0.10"
TAU="0.5"
SEEDS="42 1337 2024 31415 271828"

echo ">> [0/4] Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package >/dev/null
DEPS="$(mvn -o -q -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime dependency:build-classpath | tr -d '[:space:]')"
[ -d target/classes ] && [ -n "$DEPS" ] || { echo "Offline build failed - run prepare.sh first (while online)."; exit 1; }
export CLASSPATH="target/classes:$DEPS"

[ -f "data/weighted/per_instance/normal/$TAG.normal.s42.json" ] || {
  echo "No data yet. Run (while ONLINE): bash bench/pivot/prepare.sh"; exit 1; }

df_path(){ echo "data/weighted/per_instance/normal/$TAG.normal.s$1.json"; }

# run_one <xmx> <algo> <file> <sig> <tau> <warm> <timed> -> prints the CSV line,
# or nothing if the run produced none. SingleRun columns: 0 algo 6 mean 8 patterns 9 peak
run_one(){
  java "-Xmx$1" pivotwfsm.cli.SingleRun "$2" "$3" "$4" "$5" "$6" "$7" 2>/dev/null \
    | grep "^$2," | head -1 || true
}

mean_sd(){ # reads numbers on stdin
  awk '{v[n++]=$1; s+=$1} END{ if(n==0){print "n/a"; exit}
        m=s/n; for(i=0;i<n;i++) d+=(v[i]-m)^2; sd=(n>1)?sqrt(d/(n-1)):0;
        printf "%.1f +/- %.1f (n=%d)", m, sd, n }'
}

# ---- [1] Peak live heap (Table 1 row): -Xmx4g, no warm-up, 1 timed run -------
echo ">> [1/4] Peak live heap, -Xmx4g ..."
MEMCSV="$OUT/mem.csv"; echo "dataset,dist,algo,sigma,tau,seed,peak_heap_mb,patterns,status" > "$MEMCSV"
for a in pivot embed-min; do
  : > "/tmp/.ptc_mem_$a"
  for s in $SEEDS; do
    line="$(run_one 4g "$a" "$(df_path "$s")" "$SIG" "$TAU" 0 1)"
    if [ -n "$line" ]; then
      pk="$(echo "$line" | cut -d, -f10)"; pt="$(echo "$line" | cut -d, -f9)"
      echo "$TAG,normal,$a,$SIG,$TAU,$s,$pk,$pt,ok" >> "$MEMCSV"
      echo "$pk" >> "/tmp/.ptc_mem_$a"
    else
      echo "$TAG,normal,$a,$SIG,$TAU,$s,-1,-1,fail" >> "$MEMCSV"
    fi
  done
done

# ---- [2] Warm runtime (Table 2 row): 2 warm-up + 5 measured -----------------
echo ">> [2/4] Warm runtime (2 warm-up + 5 measured) ..."
TIMECSV="$OUT/time.csv"; echo "dataset,sigma,tau,algo,seed,mean_ms,patterns" > "$TIMECSV"
for a in pivot embed-min; do
  : > "/tmp/.ptc_time_$a"
  for s in $SEEDS; do
    line="$(run_one 4g "$a" "$(df_path "$s")" "$SIG" "$TAU" 2 5)"
    if [ -n "$line" ]; then
      ms="$(echo "$line" | cut -d, -f7)"; pt="$(echo "$line" | cut -d, -f9)"
      echo "$TAG,$SIG,$TAU,$a,$s,$ms,$pt" >> "$TIMECSV"
      echo "$ms" >> "/tmp/.ptc_time_$a"
    fi
  done
done

# ---- [3] Phase breakdown (Table 3 row): seed 42, as in reproduce.sh ---------
echo ">> [3/4] Phase breakdown ..."
PHCSV="$OUT/phases.csv"
echo "dataset,sigma,tau,patterns,total_ms,index,f1,candGen,canonical,matching,matching_pct" > "$PHCSV"
java pivotwfsm.cli.PhaseBreakdown "$(df_path 42)" "$SIG" "$TAU" 2 5 2>/dev/null | tail -1 >> "$PHCSV"

# ---- [4] Ablation 2x2 (Table 5 row) ----------------------------------------
echo ">> [4/4] Ablation 2x2 ..."
ACSV="$OUT/ablation.csv"; echo "dataset,sigma,tau,variant,seed,mean_ms" > "$ACSV"
for v in pivot pivot-nopf pivot-plain pivot-plain-nopf; do
  : > "/tmp/.ptc_abl_$v"
  for s in $SEEDS; do
    line="$(run_one 4g "$v" "$(df_path "$s")" "$SIG" "$TAU" 2 5)"
    if [ -n "$line" ]; then
      ms="$(echo "$line" | cut -d, -f7)"
      echo "$TAG,$SIG,$TAU,$v,$s,$ms" >> "$ACSV"
      echo "$ms" >> "/tmp/.ptc_abl_$v"
    fi
  done
done

# ---- Summary ---------------------------------------------------------------
echo
echo "==== PTC-MR, sigma=10%, tauW=0.5 ============================================"
printf "  peak live heap  pivot      : %s MB\n" "$(mean_sd < /tmp/.ptc_mem_pivot)"
printf "  peak live heap  embed-min  : %s MB\n" "$(mean_sd < /tmp/.ptc_mem_embed-min)"
awk 'NR==FNR{p+=$1;pn++;next}{e+=$1;en++} END{ if(pn&&en&&p>0) printf "  memory reduction           : %.1fx\n", (e/en)/(p/pn) }' \
    /tmp/.ptc_mem_pivot /tmp/.ptc_mem_embed-min
printf "  warm runtime    pivot      : %s ms\n" "$(mean_sd < /tmp/.ptc_time_pivot)"
printf "  warm runtime    embed-min  : %s ms\n" "$(mean_sd < /tmp/.ptc_time_embed-min)"
awk 'NR==FNR{p+=$1;pn++;next}{e+=$1;en++} END{ if(pn&&en&&p>0) printf "  speed-up                   : %.1fx\n", (e/en)/(p/pn) }' \
    /tmp/.ptc_time_pivot /tmp/.ptc_time_embed-min
for v in pivot pivot-nopf pivot-plain pivot-plain-nopf; do
  printf "  ablation %-17s: %s ms\n" "$v" "$(mean_sd < "/tmp/.ptc_abl_$v")"
done
echo "============================================================================"
rm -f /tmp/.ptc_mem_* /tmp/.ptc_time_* /tmp/.ptc_abl_*
echo
echo "CSV written to $OUT :"
ls -1 "$OUT" | sed 's/^/  /'
