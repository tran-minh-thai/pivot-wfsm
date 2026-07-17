#!/usr/bin/env bash
#==============================================================================
# reproduce.sh - Regenerate the full Pivot-WFSM experiment set (macOS/Linux).
#   Windows: use reproduce.ps1 (measures the exact same configurations).
#
#   bash bench/pivot/reproduce.sh quick   # MUTAG/PTC, 2 seeds, ~10 min
#   bash bench/pivot/reproduce.sh full    # +NCI, 5 seeds, OOM, baselines, ~1 h
#
# Memory = peak live heap reported by the JVM (column 10 of SingleRun). Groups:
#  [1] memory [2] OOM [3] time [4] phases [5] sensitivity [6] ablation
#  [7] published baselines [8] search-space counters.
#==============================================================================
set -u
MODE="${1:-full}"
cd "$(dirname "$0")/../.." || exit 1
OUT="results/pivot/repro"; mkdir -p "$OUT"

echo ">> [0/8] Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package 2>&1 | tail -2
export CLASSPATH="target/classes:$(mvn -o -q -Dmdep.outputFile=/dev/stdout dependency:build-classpath -DincludeScope=runtime 2>/dev/null | tail -1)"
[ -d target/classes ] || { echo "Offline build failed - run 'prepare.sh' first (while online)."; exit 1; }

# Requires the data prepared by prepare.sh. Downloads nothing -> runs offline.
if [ ! -f "data/weighted/per_instance/normal/MUTAG.normal.s42.json" ]; then
  echo "No data yet. Run (while ONLINE): bash bench/pivot/prepare.sh"; exit 1
fi

if [ "$MODE" = "quick" ]; then
  SEEDS="42 1337"
  MEM_CFG=( "MUTAG:normal:0.10:0.5" "PTC_MR:normal:0.10:0.5" )
  TIME_CFG=( "MUTAG:0.10:0.5" "PTC_MR:0.10:0.5" ); PHASE_CFG=( "${TIME_CFG[@]}" ); ABL_CFG=( "${TIME_CFG[@]}" )
  SG="0.15 0.10"; DO_OOM=0
else
  SEEDS="42 1337 2024 31415 271828"
  # Every `normal` dataset is measured at tauW=0.5, the common setting. The extra
  # rows at tauW=0.3 (MUTAG at a lower support, PTC_MR at the same support) show
  # how the advantage moves once a lower weight threshold admits more patterns.
  MEM_CFG=( "MUTAG:normal:0.10:0.5" "MUTAG:normal:0.05:0.3" \
            "PTC_MR:normal:0.10:0.5" "PTC_MR:normal:0.10:0.3" \
            "NCI1:normal:0.10:0.5" "NCI109:normal:0.10:0.5" \
            "MUTAG:nexp:0.10:0.3" "NCI1:nexp:0.10:0.3" )
  TIME_CFG=( "MUTAG:0.10:0.5" "PTC_MR:0.10:0.5" "PTC_MR:0.10:0.3" "NCI1:0.10:0.5" ); PHASE_CFG=( "${TIME_CFG[@]}" ); ABL_CFG=( "${TIME_CFG[@]}" )
  SG="0.15 0.10 0.05 0.02"; DO_OOM=1
fi

df_path(){ echo "data/weighted/per_instance/$2/$1.$2.s$3.json"; }

# run_java <xmx> <tmo> <of> <ef> -- <args...> : run one JVM with a timeout (bg+kill).
run_java(){
  local xmx=$1 tmo=$2 of=$3 ef=$4; shift 4; shift
  java -Xmx"$xmx" "$@" >"$of" 2>"$ef" & local pid=$!
  ( sleep "$tmo"; kill -9 $pid 2>/dev/null ) >/dev/null 2>&1 & local w=$!
  wait $pid 2>/dev/null; local rc=$?
  kill -9 $w 2>/dev/null; wait $w 2>/dev/null
  return $rc
}

#=============================================================================
echo ">> [1/8] Peak memory (Table 1) - peak live heap, -Xmx4g ..."
MEMCSV="$OUT/mem.csv"; echo "dataset,dist,algo,sigma,tau,seed,peak_heap_mb,patterns,status" > "$MEMCSV"
PRUNECSV="$OUT/prune.csv"; echo "dataset,dist,sigma,tau,seed,candidates,prefiltered,nonCanonical,evaluated" > "$PRUNECSV"
for cfg in "${MEM_CFG[@]}"; do
  IFS=':' read -r tag dist sig tau <<< "$cfg"
  for a in pivot embed-min; do for s in $SEEDS; do
    f=$(df_path "$tag" "$dist" "$s"); [ -f "$f" ] || { echo "MISSING $f" >&2; continue; }
    of=$(mktemp); ef=$(mktemp)
    run_java 4g 300 "$of" "$ef" -- pivotwfsm.cli.SingleRun "$a" "$f" "$sig" "$tau" 0 1; rc=$?
    line=$(cat "$of"); peak=$(echo "$line"|cut -d, -f10); pat=$(echo "$line"|cut -d, -f9)
    st=$([ $rc -eq 0 ] && [ -n "$line" ] && echo ok || (grep -qi OutOfMemory "$ef" && echo oom || echo err))
    echo "$tag,$dist,$a,$sig,$tau,$s,$peak,$pat,$st" | tee -a "$MEMCSV"
    if [ "$a" = pivot ] && [ "$st" = ok ]; then
      echo "$tag,$dist,$sig,$tau,$s,$(echo "$line"|cut -d, -f11),$(echo "$line"|cut -d, -f12),$(echo "$line"|cut -d, -f13),$(echo "$line"|cut -d, -f14)" >> "$PRUNECSV"
    fi
    rm -f "$of" "$ef"
  done; done
done

#=============================================================================
if [ "$DO_OOM" = 1 ]; then
  echo ">> [2/8] OOM (NCI1 5%/0.3, -Xmx2g) ..."
  OOMCSV="$OUT/oom.csv"; echo "algo,seed,peak_heap_mb,patterns,status" > "$OOMCSV"
  for a in pivot embed-min; do for s in $SEEDS; do
    f=$(df_path NCI1 normal "$s"); of=$(mktemp); ef=$(mktemp)
    run_java 2g 600 "$of" "$ef" -- pivotwfsm.cli.SingleRun "$a" "$f" 0.05 0.3 0 1; rc=$?
    line=$(cat "$of"); peak=$(echo "$line"|cut -d, -f10); pat=$(echo "$line"|cut -d, -f9)
    st=$([ $rc -eq 0 ] && [ -n "$line" ] && echo ok || (grep -qi OutOfMemory "$ef" && echo oom || echo err))
    echo "$a,$s,$peak,$pat,$st" | tee -a "$OOMCSV"; rm -f "$of" "$ef"
  done; done
else echo ">> [2/8] Skip OOM (quick)"; fi

#=============================================================================
echo ">> [3/8] Warm runtime (Table 2) ..."
TIMECSV="$OUT/time.csv"; echo "dataset,sigma,tau,algo,seed,mean_ms,patterns" > "$TIMECSV"
for cfg in "${TIME_CFG[@]}"; do
  IFS=':' read -r tag sig tau <<< "$cfg"
  for a in pivot embed-min; do for s in $SEEDS; do
    f=$(df_path "$tag" normal "$s"); l=$(java pivotwfsm.cli.SingleRun "$a" "$f" "$sig" "$tau" 2 5)
    echo "$tag,$sig,$tau,$a,$s,$(echo "$l"|cut -d, -f7),$(echo "$l"|cut -d, -f9)" | tee -a "$TIMECSV"
  done; done
done

#=============================================================================
echo ">> [4/8] Phase breakdown (Table 3) ..."
PHCSV="$OUT/phases.csv"; echo "dataset,sigma,tau,patterns,total_ms,index,f1,candGen,canonical,matching,matching_pct" > "$PHCSV"
for cfg in "${PHASE_CFG[@]}"; do
  IFS=':' read -r tag sig tau <<< "$cfg"
  java pivotwfsm.cli.PhaseBreakdown "$(df_path "$tag" normal 42)" "$sig" "$tau" 2 5 | tee -a "$PHCSV"
done

#=============================================================================
echo ">> [5/8] Parameter sensitivity (Table 4) - MUTAG sigma x tau ..."
SENSCSV="$OUT/sensitivity.csv"; echo "sigma,tau,patterns,pivot_mb,embed_mb,mem_reduction,pivot_ms,embed_ms,speedup" > "$SENSCSV"
f=$(df_path MUTAG normal 42)
for sig in $SG; do for tau in 0.5 0.3; do
  of=$(mktemp); ef=$(mktemp); run_java 4g 600 "$of" "$ef" -- pivotwfsm.cli.SingleRun pivot "$f" "$sig" "$tau" 0 1
  pm=$(cut -d, -f10 "$of"); pat=$(cut -d, -f9 "$of"); rm -f "$of" "$ef"
  of=$(mktemp); ef=$(mktemp); run_java 4g 600 "$of" "$ef" -- pivotwfsm.cli.SingleRun embed-min "$f" "$sig" "$tau" 0 1
  mm=$(cut -d, -f10 "$of"); rm -f "$of" "$ef"
  pv=$(java pivotwfsm.cli.SingleRun pivot "$f" "$sig" "$tau" 2 5 | cut -d, -f7)
  mg=$(java pivotwfsm.cli.SingleRun embed-min "$f" "$sig" "$tau" 2 5 | cut -d, -f7)
  redu=$(awk "BEGIN{printf \"%.2f\", ($pm>0)?$mm/$pm:0}"); sp=$(awk "BEGIN{printf \"%.2f\", ($pv>0)?$mg/$pv:0}")
  echo "$sig,$tau,$pat,$pm,$mm,$redu,$pv,$mg,$sp" | tee -a "$SENSCSV"
done; done

#=============================================================================
echo ">> [6/8] Ablation 2x2 (Table 5) ..."
ABLCSV="$OUT/ablation.csv"; echo "dataset,sigma,tau,variant,seed,mean_ms" > "$ABLCSV"
for cfg in "${ABL_CFG[@]}"; do
  IFS=':' read -r tag sig tau <<< "$cfg"
  ns="$SEEDS"
  for v in pivot pivot-nopf pivot-plain pivot-plain-nopf; do for s in $ns; do
    f=$(df_path "$tag" normal "$s"); l=$(java pivotwfsm.cli.SingleRun "$v" "$f" "$sig" "$tau" 2 5)
    echo "$tag,$sig,$tau,$v,$s,$(echo "$l"|cut -d, -f7)" | tee -a "$ABLCSV"
  done; done
done

#=============================================================================
echo ">> [7/8] Published baselines (sec 5.8) - MUTAG static ..."
BLCSV="$OUT/baselines.csv"; echo "algo,threshold,peak_heap_mb,patterns" > "$BLCSV"
fs="data/weighted/static/normal/MUTAG.normal.s42.json"
if [ -f "$fs" ]; then
  for spec in "gspan:0.10:0" "jcz-atw:0.10:0.5" "wfsm-maxpws:0:20" "wfsm-maxpws:0:10" "dewgspan:0:10"; do
    IFS=':' read -r a sg2 tw <<< "$spec"; of=$(mktemp); ef=$(mktemp)
    run_java 4g 600 "$of" "$ef" -- pivotwfsm.cli.SingleRun "$a" "$fs" "$sg2" "$tw" 0 1
    l=$(cat "$of"); echo "$a,sig=$sg2/tau=$tw,$(echo "$l"|cut -d, -f10),$(echo "$l"|cut -d, -f9)" | tee -a "$BLCSV"; rm -f "$of" "$ef"
  done
else echo "   (missing $fs - skipping baselines)"; fi

#=============================================================================
echo ">> [8/8] Aggregate mean+-sd ..."
bash bench/pivot/aggregate.sh "$OUT"
echo; echo "DONE. Raw CSVs: $OUT/"
