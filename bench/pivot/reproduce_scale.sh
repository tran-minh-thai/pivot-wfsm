#!/usr/bin/env bash
#==============================================================================
# reproduce_scale.sh - SCALE experiment (macOS/Linux). Windows: reproduce_scale.ps1.
#   Sweep database size N on a large dataset, measuring AT ONCE:
#     E1  peak memory vs N  (pivot vs embedding-storing)
#     E2  NATURAL OOM crossover point (realistic fixed heap; which side OOMs first)
#     E4  time vs N (shows it does not grow unbounded)
#   Prerequisite: prepare_scale.sh already run (while CONNECTED).
#     bash bench/pivot/reproduce_scale.sh
#   Customize: SCALE_DATASET, SCALE_SIZES, SCALE_SEEDS, SCALE_SIGMA, SCALE_TAU,
#              SCALE_HEAP (default 4g = realistic ceiling to catch natural OOM),
#              SCALE_WARM, SCALE_TIMED.
#
# SingleRun columns used here: 7=meanMs, 9=patterns, 10=peakHeapMb.
#==============================================================================
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT="results/pivot/scale"; mkdir -p "$OUT"

DATASET="${SCALE_DATASET:-Yeast}"
SIZES="${SCALE_SIZES:-1000,2000,4000,8000,16000,32000,64000,100000}"
SEEDS="${SCALE_SEEDS:-42,1337,2024}"
SIGMA="${SCALE_SIGMA:-0.10}"
TAU="${SCALE_TAU:-0.5}"
HEAP="${SCALE_HEAP:-4g}"
WARM="${SCALE_WARM:-1}"
TIMED="${SCALE_TIMED:-2}"
TMO="${SCALE_TIMEOUT:-1800}"

echo ">> [0/1] Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package 2>&1 | tail -2
export CLASSPATH="target/classes:$(mvn -o -q -Dmdep.outputFile=/dev/stdout dependency:build-classpath -DincludeScope=runtime 2>/dev/null | tail -1)"
[ -d target/classes ] || { echo "Offline build failed - run prepare_scale.sh first."; exit 1; }

# run_java <xmx> <tmo> <of> <ef> -- <args...> : 1 JVM with a timeout.
run_java(){
  local xmx=$1 tmo=$2 of=$3 ef=$4; shift 4; shift
  java -Xmx"$xmx" "$@" >"$of" 2>"$ef" & local pid=$!
  ( sleep "$tmo"; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & local w=$!
  wait $pid 2>/dev/null; local rc=$?
  kill -9 $w 2>/dev/null; wait $w 2>/dev/null
  return $rc
}

CSV="$OUT/scale.csv"
echo "dataset,N,method,seed,sigma,tau,heap,peak_heap_mb,mean_ms,patterns,status" > "$CSV"

echo ">> Sweeping N=$SIZES on $DATASET (heap=$HEAP, sigma=$SIGMA, tau=$TAU, seeds=$SEEDS)"
IFS=',' read -ra NS <<< "$SIZES"
IFS=',' read -ra SS <<< "$SEEDS"
for N in "${NS[@]}"; do
  for s in "${SS[@]}"; do
    f="data/weighted/scale/${DATASET}.normal.s${s}.n${N}.json"
    if [ ! -f "$f" ]; then echo "   [skip] missing $f"; continue; fi
    for m in pivot embed-min; do
      of=$(mktemp); ef=$(mktemp)
      run_java "$HEAP" "$TMO" "$of" "$ef" -- pivotwfsm.cli.SingleRun "$m" "$f" "$SIGMA" "$TAU" "$WARM" "$TIMED"
      rc=$?
      line=$(tail -1 "$of")
      if [ $rc -eq 0 ] && [ -n "$line" ] && ! grep -qiE "OutOfMemory|heap space" "$ef"; then
        peak=$(echo "$line" | cut -d, -f10)
        mean=$(echo "$line" | cut -d, -f7)
        pat=$(echo "$line"  | cut -d, -f9)
        status=ok
      else
        peak=-1; mean=-1; pat=-1
        if grep -qiE "OutOfMemory|heap space" "$ef"; then status=oom; else status=fail; fi
      fi
      echo "$DATASET,$N,$m,$s,$SIGMA,$TAU,$HEAP,$peak,$mean,$pat,$status" >> "$CSV"
      echo "   N=$N seed=$s $m -> $status peak=${peak}MB mean=${mean}ms pat=$pat"
      rm -f "$of" "$ef"
    done
  done
done

echo
echo "DONE. Raw results: $CSV"
echo "Summary: bash bench/pivot/aggregate_scale.sh $OUT"
