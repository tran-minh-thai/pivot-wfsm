<#
================================================================================
 minheap.ps1 [main|scale] - Measure the MINIMUM MEMORY NEEDED TO COMPLETE (Windows).

 WHY THIS METRIC: the "peak heap used" reported by the JVM is only the highest
 usage BEFORE GC runs, so it depends on how lazy GC is and on the -Xmx size, NOT
 the true memory need of the algorithm. For example PTC_MR: pivot fits entirely
 within -Xmx32m, but if given -Xmx4g it "reports" 197MB (uncollected garbage) -
 which squeezes pivot's advantage from >4x down to 1.05x, and produces an absurd
 curve (more data yet less memory).

 Instead we measure exactly what the paper claims: the SMALLEST HEAP in which the
 algorithm still completes. This metric is immune to GC timing, monotonic in the
 data, and maps almost directly to the OOM evidence.

   pwsh -ExecutionPolicy Bypass -File bench\pivot\minheap.ps1 main
   pwsh -ExecutionPolicy Bypass -File bench\pivot\minheap.ps1 scale
================================================================================
#>
param([string]$Mode = "main")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")
$OutDir = "results\pivot\minheap"; New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host ">> Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package | Out-Null
$cpFile = New-TemporaryFile
mvn -o -q "-Dmdep.outputFile=$($cpFile.FullName)" -DincludeScope=runtime dependency:build-classpath | Out-Null
$deps = (Get-Content $cpFile.FullName -Raw).Trim()
Remove-Item $cpFile.FullName -Force
if (-not (Test-Path "target\classes") -or [string]::IsNullOrWhiteSpace($deps)) {
  throw "Offline build failed - run 'prepare.ps1' first."
}
$env:CLASSPATH = "target\classes;$deps"

$ladderList = if ($env:MINHEAP_LADDER) { $env:MINHEAP_LADDER -split ' ' }
              else { @("16m","32m","64m","128m","256m","512m","1g","2g","4g","8g") }
$timeoutSec = if ($env:MINHEAP_TIMEOUT) { [int]$env:MINHEAP_TIMEOUT } else { 1800 }

# Run once at heap level $h. True = completed.
function Try-Heap([string]$h,[string]$algo,[string]$f,[double]$sig,[double]$tw) {
  $inv = [Globalization.CultureInfo]::InvariantCulture
  $o = New-TemporaryFile; $e = New-TemporaryFile
  $jargs = @("-Xmx$h","pivotwfsm.cli.SingleRun",$algo,$f,
             $sig.ToString($inv),$tw.ToString($inv),"0","1")
  $p = Start-Process -FilePath "java" -ArgumentList $jargs -NoNewWindow -PassThru `
        -RedirectStandardOutput $o.FullName -RedirectStandardError $e.FullName
  $finished = $p.WaitForExit($timeoutSec * 1000)
  if (-not $finished) { try { $p.Kill() } catch {} }
  $line = if ($finished) { Get-Content $o.FullName | Where-Object { $_ -like "$algo,*" } | Select-Object -First 1 } else { $null }
  $rc = if ($finished) { $p.ExitCode } else { -1 }
  Remove-Item $o.FullName,$e.FullName -Force
  return ($finished -and $rc -eq 0 -and $line)
}

# BINARY SEARCH for the smallest heap level in the ladder that still completes.
# Valid by monotonicity: completes at H => completes at every H' > H.
function Find-MinHeap([string]$algo,[string]$f,[double]$sig,[double]$tw) {
  $lo = 0; $hi = $ladderList.Count - 1
  if (-not (Try-Heap $ladderList[$hi] $algo $f $sig $tw)) { return ">max" }
  $best = $hi
  while ($lo -le $hi) {
    $mid = [int](($lo + $hi) / 2)
    if (Try-Heap $ladderList[$mid] $algo $f $sig $tw) { $best = $mid; $hi = $mid - 1 }
    else { $lo = $mid + 1 }
  }
  return $ladderList[$best]
}

if ($Mode -eq "scale") {
  $dataset = if ($env:SCALE_DATASET) { $env:SCALE_DATASET } else { "Yeast" }
  $sizes   = if ($env:SCALE_SIZES)   { $env:SCALE_SIZES }   else { "1000,2000,4000,8000,16000,32000,64000" }
  $seeds   = if ($env:MINHEAP_SEEDS) { $env:MINHEAP_SEEDS } else { "42,1337,2024" }
  $sigma   = if ($env:SCALE_SIGMA)   { $env:SCALE_SIGMA }   else { "0.10" }
  $tau     = if ($env:SCALE_TAU)     { $env:SCALE_TAU }     else { "0.5" }
  $csv = "$OutDir\minheap_scale.csv"
  "dataset,N,method,seed,sigma,tau,min_heap" | Set-Content $csv
  foreach ($N in $sizes -split ',') {
    foreach ($s in $seeds -split ',') {
      $f = "data\weighted\scale\$dataset.normal.s$s.n$N.json"
      if (-not (Test-Path $f)) { Write-Host "   [skip] missing $f"; continue }
      foreach ($m in @("pivot","embed-min")) {
        $h = Find-MinHeap $m $f ([double]$sigma) ([double]$tau)
        "$dataset,$N,$m,$s,$sigma,$tau,$h" | Add-Content $csv
        Write-Host "   N=$N seed=$s $m -> min-heap=$h"
      }
    }
  }
} else {
  $seeds = if ($env:MINHEAP_SEEDS) { $env:MINHEAP_SEEDS } else { "42,1337,2024,31415,271828" }
  $cfgs = @("MUTAG:normal:0.10:0.5","MUTAG:normal:0.05:0.3","PTC_MR:normal:0.10:0.3",
            "NCI1:normal:0.10:0.5","NCI109:normal:0.10:0.5",
            "MUTAG:nexp:0.10:0.3","NCI1:nexp:0.10:0.3")
  $csv = "$OutDir\minheap.csv"
  "dataset,dist,method,sigma,tau,seed,min_heap" | Set-Content $csv
  foreach ($cfg in $cfgs) {
    $tag,$dist,$sig,$tau = $cfg -split ':'
    foreach ($s in $seeds -split ',') {
      $f = "data\weighted\per_instance\$dist\$tag.$dist.s$s.json"
      if (-not (Test-Path $f)) { Write-Host "   [skip] missing $f"; continue }
      foreach ($m in @("pivot","embed-min")) {
        $h = Find-MinHeap $m $f ([double]$sig) ([double]$tau)
        "$tag,$dist,$m,$sig,$tau,$s,$h" | Add-Content $csv
        Write-Host "   $tag($dist) s=$sig t=$tau seed=$s $m -> min-heap=$h"
      }
    }
  }
}

Write-Host ""
Write-Host "DONE. Results: $csv"
