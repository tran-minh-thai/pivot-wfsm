#!/usr/bin/env bash
#==============================================================================
# prepare.sh - PREPARE phase (NEEDS NETWORK). Run ONCE before benchmarking.
#   Downloads all Maven libraries (so later runs can be offline) + downloads
#   TUDataset + generates weights. Afterwards you can DISCONNECT and run reproduce.sh.
#     bash bench/pivot/prepare.sh
# Windows: use prepare.ps1.
#==============================================================================
set -u
cd "$(dirname "$0")/../.." || exit 1

echo ">> [1/2] Build + download ALL Maven libraries/plugins (for later offline runs) ..."
mvn -q -DskipTests package 2>&1 | tail -2
mvn -q dependency:go-offline 2>&1 | tail -2
[ -d target/classes ] || { echo "Build failed"; exit 1; }

echo ">> [2/2] Download TUDataset + generate weights ..."
if [ -f "data/weighted/per_instance/normal/MUTAG.normal.s42.json" ]; then
  echo "   Data already present, skipping."
else
  command -v python3 >/dev/null || { echo "Need Python 3.10+ (python3) in PATH."; exit 1; }
  [ -d env/venv ] || python3 -m venv env/venv
  env/venv/bin/python -m pip install -q numpy pyyaml
  env/venv/bin/python data/scripts/download_tudataset.py
  env/venv/bin/python data/scripts/to_common_format.py
  env/venv/bin/python data/scripts/gen_weights.py
  [ -f "data/weighted/per_instance/normal/MUTAG.normal.s42.json" ] || { echo "Data generation failed."; exit 1; }
fi

echo
echo "PREPARATION DONE. You can now DISCONNECT the network and run the experiments:"
echo "   bash bench/pivot/reproduce.sh full"
