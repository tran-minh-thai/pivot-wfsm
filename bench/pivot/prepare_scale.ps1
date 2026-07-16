<#
================================================================================
 prepare_scale.ps1 - PREPARE phase for the SCALE experiment (NEEDS NETWORK). Run once.
   Downloads large real-labeled datasets (Yeast, NCI-H23), generates weights
   (normal, 3 seeds), and slices them into sizes N. Afterwards you can DISCONNECT and run reproduce_scale.ps1.

   pwsh -ExecutionPolicy Bypass -File bench\pivot\prepare_scale.ps1

 Customize via environment variables: SCALE_DATASETS, SCALE_SEEDS, SCALE_SIZES.
 Requirements (while connected): JDK 21, Maven, Python 3.10+ in PATH.
================================================================================
#>
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")

$dsScale    = if ($env:SCALE_DATASETS) { $env:SCALE_DATASETS } else { "Yeast,NCI-H23" }
$seedsScale = if ($env:SCALE_SEEDS)    { $env:SCALE_SEEDS }    else { "42,1337,2024" }
$sizes      = if ($env:SCALE_SIZES)    { $env:SCALE_SIZES }    else { "1000,2000,4000,8000,16000,32000,64000,100000" }

Write-Host ">> [1/4] Build + download Maven libraries (for later offline runs) ..."
mvn -q -DskipTests package | Out-Null
mvn -q dependency:go-offline | Out-Null
if (-not (Test-Path "target\classes")) { throw "Build failed" }

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
  throw "Need Python 3.10+ in PATH to generate data."
}
if (-not (Test-Path "env\venv")) { python -m venv env\venv }
$py = "env\venv\Scripts\python.exe"
& $py -m pip install -q numpy pyyaml

Write-Host ">> [2/4] Download + convert format $dsScale ..."
& $py data\scripts\download_tudataset.py --datasets $dsScale
& $py data\scripts\to_common_format.py   --datasets $dsScale

Write-Host ">> [3/4] Generate weights (normal, seeds $seedsScale) ..."
& $py data\scripts\gen_weights.py --datasets $dsScale --dists normal --seeds $seedsScale

Write-Host ">> [4/4] Slice into sizes N=$sizes ..."
New-Item -ItemType Directory -Force -Path "data\weighted\scale" | Out-Null
foreach ($ds in $dsScale -split ',') {
  foreach ($s in $seedsScale -split ',') {
    $src = "data\weighted\per_instance\normal\$ds.normal.s$s.json"
    if (-not (Test-Path $src)) { Write-Host "   [warn] missing $src, skipping"; continue }
    & $py data\scripts\subsample.py $src data\weighted\scale $sizes
  }
}

Write-Host ""
Write-Host "SCALE PREPARATION DONE. You can now DISCONNECT the network and run:"
Write-Host "   pwsh -ExecutionPolicy Bypass -File bench\pivot\reproduce_scale.ps1"
