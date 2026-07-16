#!/usr/bin/env bash
#==============================================================================
# minheap.sh [main|scale] - Measure the MINIMUM MEMORY NEEDED TO COMPLETE (macOS/Linux).
#   Windows: use minheap.ps1 (same parameter set).
#
# WHY THIS METRIC: the "peak heap used" reported by the JVM is only the highest
# usage BEFORE GC runs - it depends on how lazy GC is and on the -Xmx size, NOT
# the true memory need. For example PTC_MR: pivot fits entirely within -Xmx32m but
# if given -Xmx4g it "reports" 197MB (uncollected garbage). So we measure
# directly what the paper claims: the SMALLEST HEAP in which the algorithm still completes.
# This metric is immune to GC timing, monotonic in the data, and maps almost
# directly to the OOM evidence.
#
#   bash bench/pivot/minheap.sh main    # 4 standard sets
#   bash bench/pivot/minheap.sh scale   # min-heap curve vs N (Yeast)
#==============================================================================
set -u
MODE="${1:-main}"
cd "$(dirname "$0")/../.." || exit 1
OUT="results/pivot/minheap"; mkdir -p "$OUT"

echo ">> Build + classpath (OFFLINE) ..."
mvn -o -q -DskipTests package 2>&1 | tail -2
export CLASSPATH="target/classes:$(mvn -o -q -Dmdep.outputFile=/dev/stdout dependency:build-classpath -DincludeScope=runtime 2>/dev/null | tail -1)"
[ -d target/classes ] || { echo "Build failed"; exit 1; }

LADDER=(${MINHEAP_LADDER:-16m 32m 64m 128m 256m 512m 1g 2g 4g 8g})
SEEDS="${MINHEAP_SEEDS:-42 1337 2024 31415 271828}"
TMO="${MINHEAP_TIMEOUT:-900}"

# Run once at heap level $1; 0 = completed, non-zero = OOM/timeout.
try_heap(){ # $1=xmx $2=algo $3=file $4=sigma $5=tau
  java -Xmx"$1" pivotwfsm.cli.SingleRun "$2" "$3" "$4" "$5" 0 1 >/dev/null 2>&1 &
  local pid=$!
  ( sleep "$TMO"; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & local w=$!
  wait $pid 2>/dev/null; local rc=$?
  kill -9 $w 2>/dev/null; wait $w 2>/dev/null
  return $rc
}

# BINARY SEARCH for the smallest heap level in LADDER that still completes.
# Valid by monotonicity: completes at H => completes at every H' > H.
ladder(){ # $1=algo $2=file $3=sigma $4=tau
  local lo=0 hi=$(( ${#LADDER[@]} - 1 )) best=-1
  # Upper bound: if even the largest level fails, give up early.
  if ! try_heap "${LADDER[$hi]}" "$1" "$2" "$3" "$4"; then echo ">max"; return; fi
  best=$hi
  while [ $lo -le $hi ]; do
    local mid=$(( (lo + hi) / 2 ))
    if try_heap "${LADDER[$mid]}" "$1" "$2" "$3" "$4"; then
      best=$mid; hi=$(( mid - 1 ))
    else
      lo=$(( mid + 1 ))
    fi
  done
  echo "${LADDER[$best]}"
}

if [ "$MODE" = "scale" ]; then
  DATASET="${SCALE_DATASET:-Yeast}"
  SIZES="${SCALE_SIZES:-1000,2000,4000,8000,16000,32000,64000}"
  SEEDS="${MINHEAP_SEEDS:-42 1337 2024}"
  SIGMA="${SCALE_SIGMA:-0.10}"; TAU="${SCALE_TAU:-0.5}"
  CSV="$OUT/minheap_scale.csv"
  echo "dataset,N,method,seed,sigma,tau,min_heap" > "$CSV"
  IFS=',' read -ra NS <<< "$SIZES"
  for N in "${NS[@]}"; do
    for s in $SEEDS; do
      f="data/weighted/scale/${DATASET}.normal.s${s}.n${N}.json"
      [ -f "$f" ] || { echo "   [skip] missing $f"; continue; }
      for m in pivot embed-min; do
        h=$(ladder "$m" "$f" "$SIGMA" "$TAU")
        echo "$DATASET,$N,$m,$s,$SIGMA,$TAU,$h" >> "$CSV"
        echo "   N=$N seed=$s $m -> min-heap=$h"
      done
    done
  done
else
  CFG=( "MUTAG:normal:0.10:0.5" "MUTAG:normal:0.05:0.3" "PTC_MR:normal:0.10:0.3" \
        "NCI1:normal:0.10:0.5" "NCI109:normal:0.10:0.5" \
        "MUTAG:nexp:0.10:0.3" "NCI1:nexp:0.10:0.3" )
  CSV="$OUT/minheap.csv"
  echo "dataset,dist,method,sigma,tau,seed,min_heap" > "$CSV"
  for cfg in "${CFG[@]}"; do
    IFS=: read -r tag dist sig tau <<< "$cfg"
    for s in $SEEDS; do
      f="data/weighted/per_instance/$dist/$tag.$dist.s$s.json"
      [ -f "$f" ] || { echo "   [skip] missing $f"; continue; }
      for m in pivot embed-min; do
        h=$(ladder "$m" "$f" "$sig" "$tau")
        echo "$tag,$dist,$m,$sig,$tau,$s,$h" >> "$CSV"
        echo "   $tag($dist) s=$sig t=$tau seed=$s $m -> min-heap=$h"
      done
    done
  done
fi

echo; echo "DONE. Results: $CSV"
