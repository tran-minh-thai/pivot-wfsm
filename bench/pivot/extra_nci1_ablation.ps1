<#
================================================================================
 extra_nci1_ablation.ps1 - Re-measure the NCI1 ablation with all five seeds.

   powershell -ExecutionPolicy Bypass -File bench\pivot\extra_nci1_ablation.ps1

 Why this exists
 ---------------
 The ablation used to run NCI1 on two seeds while every other configuration used
 five, so the NCI1 row reported a standard deviation over n=2 next to rows with
 n=5. Two points do not give a meaningful spread. The exception has been removed
 from reproduce.*, and the cost turns out to be about a minute, so there was
 never a reason for it.

 This script fills that one gap without re-running the whole suite: it measures
 ONLY the NCI1 2x2 ablation at 10%/0.5 over all five seeds, with the same
 protocol, JVM flags and CSV columns as reproduce.ps1.

 Output
 ------
 results\pivot\extra\ablation_nci1.csv - same columns as the ablation.csv that
 reproduce.ps1 writes. Nothing under results\pivot\*.csv is touched.

 Requirements: JDK 21 + Maven in PATH, and data\weighted\ already generated.
 Runs offline. Takes roughly two minutes.
================================================================================
#>
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")
$OUT = "results\pivot\extra"; New-Item -ItemType Directory -Force -Path $OUT | Out-Null

$TAG      = "NCI1"
# Kept as strings so the CSV shows exactly the same field text as
# reproduce.ps1, which takes them from a -split of "NCI1:0.10:0.5".
# Run-Java casts them to double; string interpolation must not reformat them.
$SIG      = "0.10"
$TAU      = "0.5"
$SEEDS    = @(42, 1337, 2024, 31415, 271828)
$VARIANTS = @("pivot","pivot-nopf","pivot-plain","pivot-plain-nopf")

Write-Host ">> Build project + classpath (OFFLINE, -o) ..."
mvn -o -q -DskipTests package | Out-Null
$cpFile = New-TemporaryFile
mvn -o -q "-Dmdep.outputFile=$($cpFile.FullName)" -DincludeScope=runtime dependency:build-classpath | Out-Null
$deps = (Get-Content $cpFile.FullName -Raw).Trim()
Remove-Item $cpFile.FullName -Force
if (-not (Test-Path "target\classes") -or [string]::IsNullOrWhiteSpace($deps)) {
  throw "Offline build failed - run 'prepare.ps1' first (while connected to the network)."
}
$env:CLASSPATH = "target\classes;$deps"

if (-not (Test-Path "data\weighted\per_instance\normal\$TAG.normal.s42.json")) {
  throw "No data yet. Run (while CONNECTED to the network): pwsh -File bench\pivot\prepare.ps1"
}

# Run 1 JVM. Returns @{ ok; mean }. SingleRun column 6 = mean ms.
function Run-Java([string]$algo,[string]$f,[double]$sig,[double]$tau,[int]$warm,[int]$timed) {
  $o = New-TemporaryFile; $e = New-TemporaryFile
  $inv = [Globalization.CultureInfo]::InvariantCulture
  $jargs = @("-Xmx4g","pivotwfsm.cli.SingleRun",$algo,$f,
             $sig.ToString($inv),$tau.ToString($inv),"$warm","$timed")
  $p = Start-Process -FilePath "java" -ArgumentList $jargs -NoNewWindow -PassThru `
        -RedirectStandardOutput $o.FullName -RedirectStandardError $e.FullName
  if (-not $p.WaitForExit(900 * 1000)) {
    try { $p.Kill() } catch {}; Remove-Item $o.FullName,$e.FullName -Force; return @{ ok=$false }
  }
  $line = Get-Content $o.FullName | Where-Object { $_ -like "$algo,*" } | Select-Object -First 1
  Remove-Item $o.FullName,$e.FullName -Force
  if (-not $line) { return @{ ok=$false } }
  @{ ok=$true; mean=[double]($line -split ',')[6] }
}

function Stat($vals) {
  $n = $vals.Count; if ($n -eq 0) { return "n/a" }
  $m = ($vals | Measure-Object -Average).Average
  $sd = 0.0; if ($n -gt 1) { foreach ($v in $vals) { $sd += ($v-$m)*($v-$m) }; $sd = [math]::Sqrt($sd/($n-1)) }
  "{0:N0} +/- {1:N0} (n={2})" -f $m,$sd,$n
}

Write-Host ">> NCI1 ablation 2x2, sigma=10%, tauW=0.5, five seeds ..."
$aCsv = "$OUT\ablation_nci1.csv"; "dataset,sigma,tau,variant,seed,mean_ms" | Set-Content $aCsv
$agg = @{}
foreach ($v in $VARIANTS) {
  foreach ($s in $SEEDS) {
    $f = "data\weighted\per_instance\normal\$TAG.normal.s$s.json"
    $r = Run-Java $v $f $SIG $TAU 2 5
    if ($r.ok) {
      "$TAG,$SIG,$TAU,$v,$s,$($r.mean)" | Add-Content $aCsv
      if (-not $agg[$v]) { $agg[$v] = @() }; $agg[$v] += $r.mean
      Write-Host ("   {0,-18} seed {1,-7} {2} ms" -f $v, $s, $r.mean)
    } else {
      Write-Host "   $v seed $s FAILED"
    }
  }
}

Write-Host ""
Write-Host "==== NCI1 ablation, sigma=10%, tauW=0.5, five seeds ========================"
foreach ($v in $VARIANTS) { Write-Host ("  {0,-18}: {1} ms" -f $v, (Stat $agg[$v])) }
if ($agg["pivot"] -and $agg["pivot-plain"]) {
  $p = ($agg["pivot"] | Measure-Object -Average).Average
  $q = ($agg["pivot-plain"] | Measure-Object -Average).Average
  if ($p -gt 0) { Write-Host ("  pivot anchoring (plain/pivot, with pf) : {0:N2}x" -f ($q/$p)) }
}
Write-Host "============================================================================"
Write-Host ""
Write-Host "CSV written to $aCsv"
