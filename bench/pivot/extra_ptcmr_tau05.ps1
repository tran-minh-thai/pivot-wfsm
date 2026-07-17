<#
================================================================================
 extra_ptcmr_tau05.ps1 - Measure the ONE configuration the suite was missing:
 PTC-MR at tauW=0.5.

   powershell -ExecutionPolicy Bypass -File bench\pivot\extra_ptcmr_tau05.ps1

 Why this exists
 ---------------
 Every normal-distribution dataset was measured at tauW=0.5 except PTC-MR, which
 was only ever measured at 0.3. The two are not interchangeable: at 0.5 PTC-MR
 admits far fewer patterns, so the embedding store stays small and the memory
 advantage narrows, while the runtime advantage widens. Reporting both makes the
 common setting present for every dataset and shows that effect instead of
 leaving it implied.

 reproduce.ps1 now includes this configuration, so a future full run picks it up.
 This script exists so you do not have to re-run the whole suite just to fill one
 gap: it measures ONLY PTC-MR at 10%/0.5, with the same protocol, the same JVM
 flags and the same CSV columns as reproduce.ps1.

 Output
 ------
 results\pivot\extra\{mem,time,phases,ablation}.csv - same columns as the files
 reproduce.ps1 writes, so the rows can be read alongside the existing ones.
 Nothing under results\pivot\*.csv (the reference set) is touched.

 Requirements: JDK 21 + Maven in PATH, and data\weighted\ already generated
 (see bench\pivot\README_REPRO.md). Runs offline. Takes a few minutes.
================================================================================
#>
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")
$OUT = "results\pivot\extra"; New-Item -ItemType Directory -Force -Path $OUT | Out-Null

$TAG   = "PTC_MR"
# Kept as strings so the CSV shows exactly the same field text as
# reproduce.ps1, which takes them from a -split of "PTC_MR:normal:0.10:0.5".
# Run-Java casts them to double; string interpolation must not reformat them.
$SIG   = "0.10"
$TAU   = "0.5"
$SEEDS = @(42, 1337, 2024, 31415, 271828)

Write-Host ">> [0/4] Build project + classpath (OFFLINE, -o) ..."
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

function Df([int]$seed) { "data\weighted\per_instance\normal\$TAG.normal.s$seed.json" }

# Run 1 JVM. Returns @{ ok; mean; patterns; peak }.
# SingleRun columns: 0 algo 6 mean 8 patterns 9 peakLiveHeapMb
function Run-Java([string]$xmx,[int]$timeoutSec,[string]$algo,[string]$f,
                  [double]$sig,[double]$tau,[int]$warm,[int]$timed) {
  $o = New-TemporaryFile; $e = New-TemporaryFile
  $inv = [Globalization.CultureInfo]::InvariantCulture
  $jargs = @("-Xmx$xmx","pivotwfsm.cli.SingleRun",$algo,$f,
             $sig.ToString($inv),$tau.ToString($inv),"$warm","$timed")
  $p = Start-Process -FilePath "java" -ArgumentList $jargs -NoNewWindow -PassThru `
        -RedirectStandardOutput $o.FullName -RedirectStandardError $e.FullName
  if (-not $p.WaitForExit($timeoutSec * 1000)) {
    try { $p.Kill() } catch {}; Remove-Item $o.FullName,$e.FullName -Force; return @{ ok=$false }
  }
  $line = Get-Content $o.FullName | Where-Object { $_ -like "$algo,*" } | Select-Object -First 1
  Remove-Item $o.FullName,$e.FullName -Force
  if (-not $line) { return @{ ok=$false } }
  $c = $line -split ','
  @{ ok=$true; mean=[double]$c[6]; patterns=[int]$c[8]; peak=[int]$c[9] }
}

function Stat($vals) {
  $n = $vals.Count; if ($n -eq 0) { return "n/a" }
  $m = ($vals | Measure-Object -Average).Average
  $sd = 0.0; if ($n -gt 1) { foreach ($v in $vals) { $sd += ($v-$m)*($v-$m) }; $sd = [math]::Sqrt($sd/($n-1)) }
  "{0:N1} +/- {1:N1} (n={2})" -f $m,$sd,$n
}

# ---- [1] Peak live heap (Table 1 row) : -Xmx4g, no warm-up, 1 timed run ------
Write-Host ">> [1/4] Peak live heap, -Xmx4g ..."
$memCsv = "$OUT\mem.csv"; "dataset,dist,algo,sigma,tau,seed,peak_heap_mb,patterns,status" | Set-Content $memCsv
$memAgg = @{}
foreach ($a in @("pivot","embed-min")) {
  foreach ($s in $SEEDS) {
    $r = Run-Java "4g" 900 $a (Df $s) $SIG $TAU 0 1
    $pk = if ($r.ok) { $r.peak } else { -1 }
    $pt = if ($r.ok) { $r.patterns } else { -1 }
    $st = if ($r.ok) { "ok" } else { "fail" }
    "$TAG,normal,$a,$SIG,$TAU,$s,$pk,$pt,$st" | Add-Content $memCsv
    if ($r.ok) { if (-not $memAgg[$a]) { $memAgg[$a] = @() }; $memAgg[$a] += $r.peak }
  }
}

# ---- [2] Warm runtime (Table 2 row) : 2 warm-up + 5 measured ----------------
Write-Host ">> [2/4] Warm runtime (2 warm-up + 5 measured) ..."
$timeCsv = "$OUT\time.csv"; "dataset,sigma,tau,algo,seed,mean_ms,patterns" | Set-Content $timeCsv
$timeAgg = @{}
foreach ($a in @("pivot","embed-min")) {
  foreach ($s in $SEEDS) {
    $r = Run-Java "4g" 900 $a (Df $s) $SIG $TAU 2 5
    if ($r.ok) {
      "$TAG,$SIG,$TAU,$a,$s,$($r.mean),$($r.patterns)" | Add-Content $timeCsv
      if (-not $timeAgg[$a]) { $timeAgg[$a] = @() }; $timeAgg[$a] += $r.mean
    }
  }
}

# ---- [3] Phase breakdown (Table 3 row) : seed 42, as in reproduce.ps1 -------
Write-Host ">> [3/4] Phase breakdown ..."
$phCsv = "$OUT\phases.csv"
"dataset,sigma,tau,patterns,total_ms,index,f1,candGen,canonical,matching,matching_pct" | Set-Content $phCsv
$phaseOut = & java pivotwfsm.cli.PhaseBreakdown (Df 42) $SIG $TAU 2 5
($phaseOut | Select-Object -Last 1) | Add-Content $phCsv

# ---- [4] Ablation 2x2 (Table 5 row) ----------------------------------------
Write-Host ">> [4/4] Ablation 2x2 ..."
$aCsv = "$OUT\ablation.csv"; "dataset,sigma,tau,variant,seed,mean_ms" | Set-Content $aCsv
$aAgg = @{}
foreach ($v in @("pivot","pivot-nopf","pivot-plain","pivot-plain-nopf")) {
  foreach ($s in $SEEDS) {
    $r = Run-Java "4g" 900 $v (Df $s) $SIG $TAU 2 5
    if ($r.ok) {
      "$TAG,$SIG,$TAU,$v,$s,$($r.mean)" | Add-Content $aCsv
      if (-not $aAgg[$v]) { $aAgg[$v] = @() }; $aAgg[$v] += $r.mean
    }
  }
}

# ---- Summary ----------------------------------------------------------------
Write-Host ""
Write-Host "==== PTC-MR, sigma=10%, tauW=0.5 ============================================"
Write-Host ("  peak live heap  pivot      : {0} MB" -f (Stat $memAgg["pivot"]))
Write-Host ("  peak live heap  embed-min  : {0} MB" -f (Stat $memAgg["embed-min"]))
if ($memAgg["pivot"] -and $memAgg["embed-min"]) {
  $mp = ($memAgg["pivot"] | Measure-Object -Average).Average
  $me = ($memAgg["embed-min"] | Measure-Object -Average).Average
  if ($mp -gt 0) { Write-Host ("  memory reduction           : {0:N1}x" -f ($me/$mp)) }
}
Write-Host ("  warm runtime    pivot      : {0} ms" -f (Stat $timeAgg["pivot"]))
Write-Host ("  warm runtime    embed-min  : {0} ms" -f (Stat $timeAgg["embed-min"]))
if ($timeAgg["pivot"] -and $timeAgg["embed-min"]) {
  $tp = ($timeAgg["pivot"] | Measure-Object -Average).Average
  $te = ($timeAgg["embed-min"] | Measure-Object -Average).Average
  if ($tp -gt 0) { Write-Host ("  speed-up                   : {0:N1}x" -f ($te/$tp)) }
}
foreach ($v in @("pivot","pivot-nopf","pivot-plain","pivot-plain-nopf")) {
  Write-Host ("  ablation {0,-17}: {1} ms" -f $v, (Stat $aAgg[$v]))
}
Write-Host "============================================================================"
Write-Host ""
Write-Host "CSV written to $OUT :"
Get-ChildItem $OUT | ForEach-Object { Write-Host ("  " + $_.Name) }
