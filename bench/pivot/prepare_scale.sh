#!/usr/bin/env bash
#==============================================================================
# prepare_scale.sh - PREPARE phase for the SCALE experiment (NEEDS NETWORK). Run once.
#   Downloads large real-labeled datasets (Yeast, NCI-H23), generates weights
#   (normal, 3 seeds), and slices them into sizes N to sweep the memory curve.
#   Afterwards you can DISCONNECT and run reproduce_scale.sh.
#     bash bench/pivot/prepare_scale.sh
#   Customize via environment variables: SCALE_DATASETS, SCALE_SEEDS, SCALE_SIZES.
# Windows: use prepare_scale.ps1.
#==============================================================================
set -u
cd "$(dirname "$0")/../.." || exit 1

DATASETS_SCALE="${SCALE_DATASETS:-Yeast,NCI-H23}"
SEEDS_SCALE="${SCALE_SEEDS:-42,1337,2024}"
SIZES="${SCALE_SIZES:-1000,2000,4000,8000,16000,32000,64000,100000}"

echo ">> [1/4] Build + download Maven libraries (for later offline runs) ..."
mvn -q -DskipTests package 2>&1 | tail -2
mvn -q dependency:go-offline 2>&1 | tail -2
[ -d target/classes ] || { echo "Build failed"; exit 1; }

command -v python3 >/dev/null || { echo "Need Python 3.10+ (python3) in PATH."; exit 1; }
[ -d env/venv ] || python3 -m venv env/venv
PY=env/venv/bin/python
$PY -m pip install -q numpy pyyaml

echo ">> [2/4] Download + convert format $DATASETS_SCALE ..."
$PY data/scripts/download_tudataset.py --datasets "$DATASETS_SCALE"
$PY data/scripts/to_common_format.py   --datasets "$DATASETS_SCALE"

echo ">> [3/4] Generate weights (normal, seeds $SEEDS_SCALE) ..."
$PY data/scripts/gen_weights.py --datasets "$DATASETS_SCALE" --dists normal --seeds "$SEEDS_SCALE"

echo ">> [4/4] Slice into sizes N=$SIZES ..."
mkdir -p data/weighted/scale
IFS=',' read -ra DS <<< "$DATASETS_SCALE"
IFS=',' read -ra SS <<< "$SEEDS_SCALE"
for ds in "${DS[@]}"; do
  for s in "${SS[@]}"; do
    src="data/weighted/per_instance/normal/${ds}.normal.s${s}.json"
    [ -f "$src" ] || { echo "   [warn] missing $src, skipping"; continue; }
    $PY data/scripts/subsample.py "$src" data/weighted/scale "$SIZES"
  done
done

echo
echo "SCALE PREPARATION DONE. You can now DISCONNECT the network and run:"
echo "   bash bench/pivot/reproduce_scale.sh"
