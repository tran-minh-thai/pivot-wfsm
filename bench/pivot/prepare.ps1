<#
================================================================================
 prepare.ps1 - PREPARE phase (NEEDS NETWORK). Run ONCE before the experiments.
   Downloads all Maven libraries (so later runs can be offline) + downloads
   TUDataset + generates weights. Afterwards you can DISCONNECT and run reproduce.ps1.

   pwsh -ExecutionPolicy Bypass -File bench\pivot\prepare.ps1

 Requirements (while connected): JDK 21, Maven, Python 3.10+ in PATH.
================================================================================
#>
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")

Write-Host ">> [1/2] Build + download ALL Maven libraries/plugins (for later offline runs) ..."
mvn -q -DskipTests package
mvn -q dependency:go-offline
if (-not (Test-Path "target\classes")) { throw "Build failed" }

Write-Host ">> [2/2] Download TUDataset + generate weights ..."
if (Test-Path "data\weighted\per_instance\normal\MUTAG.normal.s42.json") {
  Write-Host "   Data already present, skipping."
} else {
  if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Need Python 3.10+ in PATH to generate data."
  }
  if (-not (Test-Path "env\venv")) { python -m venv env\venv }
  $py = "env\venv\Scripts\python.exe"
  & $py -m pip install -q numpy pyyaml
  & $py data\scripts\download_tudataset.py
  & $py data\scripts\to_common_format.py
  & $py data\scripts\gen_weights.py
  if (-not (Test-Path "data\weighted\per_instance\normal\MUTAG.normal.s42.json")) {
    throw "Data generation failed - check the network connection / Python."
  }
}

Write-Host ""
Write-Host "PREPARATION DONE. You can now DISCONNECT the network and run the experiments:"
Write-Host "   pwsh -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 full"
