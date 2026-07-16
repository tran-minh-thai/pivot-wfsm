<#
================================================================================
 reproduce_scale.ps1 - SCALE experiment (Windows). Measures AT ONCE:
   E1  peak memory vs N  (pivot vs embedding-storing)
   E2  NATURAL OOM crossover point (realistic fixed heap; which side OOMs first)
   E4  time vs N (shows it does not grow unbounded)
 Prerequisite: prepare_scale.ps1 already run (while CONNECTED).

   pwsh -ExecutionPolicy Bypass -File bench\pivot\reproduce_scale.ps1

 Customize via environment variables: SCALE_DATASET, SCALE_SIZES, SCALE_SEEDS,
   SCALE_SIGMA, SCALE_TAU, SCALE_HEAP (default 4g), SCALE_WARM, SCALE_TIMED.
 SingleRun columns used here: index 6=meanMs, 8=patterns, 9=peakHeapMb.
================================================================================
#>
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")
$OutDir = "results\pivot\scale"; New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$dataset = if ($env:SCALE_DATASET) { $env:SCALE_DATASET } else { "Yeast" }
$sizes   = if ($env:SCALE_SIZES)   { $env:SCALE_SIZES }   else { "1000,2000,4000,8000,16000,32000,64000,100000" }
$seeds   = if ($env:SCALE_SEEDS)   { $env:SCALE_SEEDS }   else { "42,1337,2024" }
$sigma   = if ($env:SCALE_SIGMA)   { $env:SCALE_SIGMA }   else { "0.10" }
$tau     = if ($env:SCALE_TAU)     { $env:SCALE_TAU }     else { "0.5" }
$heap    = if ($env:SCALE_HEAP)    { $env:SCALE_HEAP }    else { "4g" }
$warm    = if ($env:SCALE_WARM)    { [int]$env:SCALE_WARM }  else { 1 }
$timed   = if ($env:SCALE_TIMED)   { [int]$env:SCALE_TIMED } else { 2 }
$tmo     = if ($env:SCALE_TIMEOUT) { [int]$env:SCALE_TIMEOUT } else { 1800 }

Write-Host ">> [0/1] Build + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package | Out-Null
$cpFile = New-TemporaryFile
mvn -o -q "-Dmdep.outputFile=$($cpFile.FullName)" -DincludeScope=runtime dependency:build-classpath | Out-Null
$deps = (Get-Content $cpFile.FullName -Raw).Trim()
Remove-Item $cpFile.FullName -Force
if (-not (Test-Path "target\classes") -or [string]::IsNullOrWhiteSpace($deps)) {
  throw "Offline build failed - run 'prepare_scale.ps1' first (while connected to the network)."
}
$env:CLASSPATH = "target\classes;$deps"

# Run 1 JVM with a timeout. Returns @{ ok; oom; mean; patterns; peak }.
function Run-JavaScale([string]$xmx,[int]$timeoutSec,[string]$algo,[string]$f,
                       [double]$sig,[double]$tw,[int]$w,[int]$t) {
  $o = New-TemporaryFile; $e = New-TemporaryFile
  $inv = [Globalization.CultureInfo]::InvariantCulture
  $jargs = @("-Xmx$xmx","pivotwfsm.cli.SingleRun",$algo,$f,
             $sig.ToString($inv),$tw.ToString($inv),"$w","$t")
  $p = Start-Process -FilePath "java" -ArgumentList $jargs -NoNewWindow -PassThru `
        -RedirectStandardOutput $o.FullName -RedirectStandardError $e.FullName
  if (-not $p.WaitForExit($timeoutSec * 1000)) {
    try { $p.Kill() } catch {}; Remove-Item $o.FullName,$e.FullName -Force; return @{ ok=$false; oom=$false }
  }
  $line = Get-Content $o.FullName | Where-Object { $_ -like "$algo,*" } | Select-Object -First 1
  $isOom = (Select-String -Path $e.FullName -Pattern "OutOfMemoryError" -Quiet) -eq $true
  Remove-Item $o.FullName,$e.FullName -Force
  if (-not $line) { return @{ ok=$false; oom=$isOom } }
  $c = $line -split ','
  @{ ok=$true; oom=$false; mean=[double]$c[6]; patterns=[int]$c[8]; peak=[int]$c[9] }
}

$csv = "$OutDir\scale.csv"
"dataset,N,method,seed,sigma,tau,heap,peak_heap_mb,mean_ms,patterns,status" | Set-Content $csv

Write-Host ">> Sweeping N=$sizes on $dataset (heap=$heap, sigma=$sigma, tau=$tau, seeds=$seeds)"
foreach ($N in $sizes -split ',') {
  foreach ($s in $seeds -split ',') {
    $f = "data\weighted\scale\$dataset.normal.s$s.n$N.json"
    if (-not (Test-Path $f)) { Write-Host "   [skip] missing $f"; continue }
    foreach ($m in @("pivot","embed-min")) {
      $r = Run-JavaScale $heap $tmo $m $f ([double]$sigma) ([double]$tau) $warm $timed
      if ($r.ok)       { $peak=$r.peak; $mean=$r.mean; $pat=$r.patterns; $status="ok" }
      elseif ($r.oom)  { $peak=-1; $mean=-1; $pat=-1; $status="oom" }
      else             { $peak=-1; $mean=-1; $pat=-1; $status="fail" }
      "$dataset,$N,$m,$s,$sigma,$tau,$heap,$peak,$mean,$pat,$status" | Add-Content $csv
      Write-Host "   N=$N seed=$s $m -> $status peak=$($peak)MB mean=$($mean)ms pat=$pat"
    }
  }
}

Write-Host ""
Write-Host "DONE. Raw results: $csv"
Write-Host "Summary (on a machine with bash): bash bench/pivot/aggregate_scale.sh $OutDir"
