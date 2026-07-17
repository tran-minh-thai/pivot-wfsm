<#
================================================================================
 reproduce.ps1 - Reproduce the ENTIRE Pivot-WFSM experiment suite on Windows.

   powershell -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 quick
   powershell -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 full

 Requirements: JDK 21 + Maven in PATH, and data\weighted\ already generated (see README_REPRO).
 Memory = peak live heap reported by the JVM itself - platform independent.
 Measures every metric the paper needs:
   [1] Peak memory (Table 1)    [2] OOM        [3] Warm timing (Table 2)
   [4] Phase breakdown (Table 3)  [5] Sensitivity (Table 4)  [6] Ablation (Table 5)
   [7] Published baselines (section 5.8)   [8] Pruning stats (candidates/prefiltered)
================================================================================
#>
param([string]$Mode = "full")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\..")
$OUT = "results\pivot\repro"; New-Item -ItemType Directory -Force -Path $OUT | Out-Null

Write-Host ">> [0/8] Build project + classpath (OFFLINE, -o) ..."
# -o = offline mode: use the libraries cached by prepare.ps1, do NOT use the network.
mvn -o -q -DskipTests package | Out-Null
$cpFile = New-TemporaryFile
mvn -o -q "-Dmdep.outputFile=$($cpFile.FullName)" -DincludeScope=runtime dependency:build-classpath | Out-Null
$deps = (Get-Content $cpFile.FullName -Raw).Trim()
Remove-Item $cpFile.FullName -Force
if (-not (Test-Path "target\classes") -or [string]::IsNullOrWhiteSpace($deps)) {
  throw "Offline build failed - run 'prepare.ps1' first (while connected to the network)."
}
# Classpath via environment variable: avoids quoting errors when the path contains spaces.
$env:CLASSPATH = "target\classes;$deps"

# Requires data already prepared (via prepare.ps1). Nothing is downloaded here -> runs offline.
if (-not (Test-Path "data\weighted\per_instance\normal\MUTAG.normal.s42.json")) {
  throw "No data yet. Run (while CONNECTED to the network): pwsh -File bench\pivot\prepare.ps1"
}

# ---- Parameters by mode -----------------------------------------------------
if ($Mode -eq "quick") {
  $SEEDS = @(42,1337)
  $MEM   = @("MUTAG:normal:0.10:0.5","PTC_MR:normal:0.10:0.5")
  $TIMEC = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5")
  $PHASE = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5")
  $ABL   = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5")
  $SG    = @(0.15,0.10)
  $doOOM = $false
} else {
  $SEEDS = @(42,1337,2024,31415,271828)
  $MEM   = @("MUTAG:normal:0.10:0.5","MUTAG:normal:0.05:0.3",
             "PTC_MR:normal:0.10:0.5","PTC_MR:normal:0.10:0.3",
             "NCI1:normal:0.10:0.5","NCI109:normal:0.10:0.5",
             "MUTAG:nexp:0.10:0.3","NCI1:nexp:0.10:0.3")
  $TIMEC = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5","PTC_MR:0.10:0.3","NCI1:0.10:0.5")
  $PHASE = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5","PTC_MR:0.10:0.3","NCI1:0.10:0.5")
  $ABL   = @("MUTAG:0.10:0.5","PTC_MR:0.10:0.5","PTC_MR:0.10:0.3","NCI1:0.10:0.5")
  $SG    = @(0.15,0.10,0.05,0.02)
  $doOOM = $true
}

function Df([string]$tag,[string]$dist,[int]$seed) { "data\weighted\per_instance\$dist\$tag.$dist.s$seed.json" }

# Run 1 JVM. Returns @{ ok; mean; patterns; peak; cand; pref; noncanon; eval; oom }.
# SingleRun columns: 0 algo 6 mean 8 patterns 9 peak 10 cand 11 pref 12 noncanon 13 eval
function Run-Java([string]$xmx,[int]$timeoutSec,[string]$algo,[string]$f,
                  [double]$sig,[double]$tau,[int]$warm,[int]$timed) {
  $o = New-TemporaryFile; $e = New-TemporaryFile
  $inv = [Globalization.CultureInfo]::InvariantCulture
  $jargs = @("-Xmx$xmx","pivotwfsm.cli.SingleRun",$algo,$f,
             $sig.ToString($inv),$tau.ToString($inv),"$warm","$timed")
  $p = Start-Process -FilePath "java" -ArgumentList $jargs -NoNewWindow -PassThru `
        -RedirectStandardOutput $o.FullName -RedirectStandardError $e.FullName
  if (-not $p.WaitForExit($timeoutSec * 1000)) {
    try { $p.Kill() } catch {}; Remove-Item $o.FullName,$e.FullName -Force; return @{ ok=$false; oom=$false }
  }
  $line = Get-Content $o.FullName | Where-Object { $_ -like "$algo,*" } | Select-Object -First 1
  $oom  = (Select-String -Path $e.FullName -Pattern "OutOfMemoryError" -Quiet) -eq $true
  Remove-Item $o.FullName,$e.FullName -Force
  if (-not $line) { return @{ ok=$false; oom=$oom } }
  $c = $line -split ','
  @{ ok=$true; mean=[double]$c[6]; patterns=[int]$c[8]; peak=[int]$c[9];
     cand=[long]$c[10]; pref=[long]$c[11]; noncanon=[long]$c[12]; eval=[long]$c[13]; oom=$false }
}

function Stat($vals) {
  $n = $vals.Count; if ($n -eq 0) { return "n/a" }
  $m = ($vals | Measure-Object -Average).Average
  $sd = 0.0; if ($n -gt 1) { foreach ($v in $vals) { $sd += ($v-$m)*($v-$m) }; $sd = [math]::Sqrt($sd/($n-1)) }
  "{0:N0} +/- {1:N0} (n={2})" -f $m,$sd,$n
}

# ============================ [1] Memory (Table 1) =============================
Write-Host ">> [1/8] Peak memory (Table 1) - peak heap, -Xmx4g ..."
$memCsv = "$OUT\mem.csv"; "dataset,dist,algo,sigma,tau,seed,peak_heap_mb,patterns,status" | Set-Content $memCsv
$pruneCsv = "$OUT\prune.csv"; "dataset,dist,sigma,tau,seed,candidates,prefiltered,nonCanonical,evaluated" | Set-Content $pruneCsv
$memAgg = @{}; $pruneAgg = @{}
foreach ($cfg in $MEM) {
  $tag,$dist,$sig,$tau = $cfg -split ':'
  foreach ($a in @("pivot","embed-min")) { foreach ($s in $SEEDS) {
    $f = Df $tag $dist $s; if (-not (Test-Path $f)) { Write-Host "MISSING $f"; continue }
    $r = Run-Java "4g" 300 $a $f ([double]$sig) ([double]$tau) 0 1
    $st = if ($r.ok) {"ok"} elseif ($r.oom) {"oom"} else {"err"}
    $pk = if ($r.ok) {$r.peak} else {""}; $pt = if ($r.ok) {$r.patterns} else {""}
    "$tag,$dist,$a,$sig,$tau,$s,$pk,$pt,$st" | Add-Content $memCsv
    if ($r.ok) {
      $k="$tag($dist)/$a s=$sig t=$tau"; if (-not $memAgg[$k]){$memAgg[$k]=@()}; $memAgg[$k]+=$r.peak
      if ($a -eq "pivot") {
        $pruneAgg["$tag($dist) s=$sig t=$tau"] = "cand=$($r.cand) pref=$($r.pref) nonCanon=$($r.noncanon) eval=$($r.eval)"
        "$tag,$dist,$sig,$tau,$s,$($r.cand),$($r.pref),$($r.noncanon),$($r.eval)" | Add-Content $pruneCsv
      }
    }
  }}
}

# ============================ [2] OOM ========================================
if ($doOOM) {
  Write-Host ">> [2/8] OOM (NCI1 5%/0.3, -Xmx2g) ..."
  $oomCsv = "$OUT\oom.csv"; "algo,seed,peak_heap_mb,patterns,status" | Set-Content $oomCsv
  foreach ($a in @("pivot","embed-min")) { foreach ($s in $SEEDS) {
    $r = Run-Java "2g" 600 $a (Df "NCI1" "normal" $s) 0.05 0.3 0 1
    $st = if ($r.ok) {"ok"} elseif ($r.oom) {"oom"} else {"err/timeout"}
    $pk = if ($r.ok) {$r.peak} else {""}; $pt = if ($r.ok) {$r.patterns} else {""}
    "$a,$s,$pk,$pt,$st" | Add-Content $oomCsv
  }}
} else { Write-Host ">> [2/8] Skip OOM (quick mode)" }

# ============================ [3] Timing (Table 2) =========================
Write-Host ">> [3/8] Warm timing (Table 2) - 2 warm-up + 5 measured ..."
$timeCsv = "$OUT\time.csv"; "dataset,sigma,tau,algo,seed,mean_ms,patterns" | Set-Content $timeCsv
$timeAgg = @{}
foreach ($cfg in $TIMEC) {
  $tag,$sig,$tau = $cfg -split ':'
  foreach ($a in @("pivot","embed-min")) { foreach ($s in $SEEDS) {
    $r = Run-Java "4g" 900 $a (Df $tag "normal" $s) ([double]$sig) ([double]$tau) 2 5
    if ($r.ok) { "$tag,$sig,$tau,$a,$s,$($r.mean),$($r.patterns)" | Add-Content $timeCsv
      $k="$tag s=$sig t=$tau/$a"; if (-not $timeAgg[$k]){$timeAgg[$k]=@()}; $timeAgg[$k]+=$r.mean }
  }}
}

# ============================ [4] Phase (Table 3) ===============================
Write-Host ">> [4/8] Phase breakdown (Table 3) ..."
$phCsv = "$OUT\phases.csv"; "dataset,sigma,tau,patterns,total_ms,index,f1,candGen,canonical,matching,matching_pct" | Set-Content $phCsv
foreach ($cfg in $PHASE) {
  $tag,$sig,$tau = $cfg -split ':'
  # NOTE: PowerShell is NOT case-sensitive for variable names, so do NOT name
  # this variable $out (it would clobber $OUT = the results directory).
  $phaseOut = & java pivotwfsm.cli.PhaseBreakdown (Df $tag "normal" 42) $sig $tau 2 5
  ($phaseOut | Select-Object -Last 1) | Add-Content $phCsv
}

# ============================ [5] Sensitivity (Table 4) ==========================
Write-Host ">> [5/8] Parameter sensitivity (Table 4) - MUTAG sigma x tau ..."
$sCsv = "$OUT\sensitivity.csv"; "sigma,tau,patterns,pivot_mb,embed_mb,mem_reduction,pivot_ms,embed_ms,speedup" | Set-Content $sCsv
foreach ($sig in $SG) { foreach ($tau in @(0.5,0.3)) {
  $pm = Run-Java "4g" 900 "pivot" (Df "MUTAG" "normal" 42) $sig $tau 0 1
  $mm = Run-Java "4g" 900 "embed-min" (Df "MUTAG" "normal" 42) $sig $tau 0 1
  $pt = Run-Java "4g" 900 "pivot" (Df "MUTAG" "normal" 42) $sig $tau 2 5
  $mt = Run-Java "4g" 900 "embed-min" (Df "MUTAG" "normal" 42) $sig $tau 2 5
  if ($pm.ok -and $mm.ok) {
    $red = if ($pm.peak -gt 0) { [math]::Round($mm.peak/$pm.peak,2) } else { 0 }
    $sp  = if ($pt.mean -gt 0) { [math]::Round($mt.mean/$pt.mean,2) } else { 0 }
    "$sig,$tau,$($pm.patterns),$($pm.peak),$($mm.peak),$red,$($pt.mean),$($mt.mean),$sp" | Add-Content $sCsv
  }
}}

# ============================ [6] Ablation (Table 5) =========================
Write-Host ">> [6/8] Ablation 2x2 (Table 5) ..."
$aCsv = "$OUT\ablation.csv"; "dataset,sigma,tau,variant,seed,mean_ms" | Set-Content $aCsv
$aAgg = @{}
foreach ($cfg in $ABL) {
  $tag,$sig,$tau = $cfg -split ':'
  $ns = $SEEDS
  foreach ($v in @("pivot","pivot-nopf","pivot-plain","pivot-plain-nopf")) { foreach ($s in $ns) {
    $r = Run-Java "4g" 900 $v (Df $tag "normal" $s) ([double]$sig) ([double]$tau) 2 5
    if ($r.ok) { "$tag,$sig,$tau,$v,$s,$($r.mean)" | Add-Content $aCsv
      $k="$tag s=$sig t=$tau [$v]"; if (-not $aAgg[$k]){$aAgg[$k]=@()}; $aAgg[$k]+=$r.mean }
  }}
}

# ============================ [7] Published baselines (section 5.8) ================
Write-Host ">> [7/8] Published baselines (section 5.8) - MUTAG static ..."
$blCsv = "$OUT\baselines.csv"; "algo,threshold,peak_heap_mb,patterns" | Set-Content $blCsv
$fs = "data\weighted\static\normal\MUTAG.normal.s42.json"
if (Test-Path $fs) {
  foreach ($spec in @("gspan:0.10:0","jcz-atw:0.10:0.5","wfsm-maxpws:0:20","wfsm-maxpws:0:10","dewgspan:0:10")) {
    $a,$sg2,$tw = $spec -split ':'
    $r = Run-Java "4g" 600 $a $fs ([double]$sg2) ([double]$tw) 0 1
    if ($r.ok) { "$a,sig=$sg2/tau=$tw,$($r.peak),$($r.patterns)" | Add-Content $blCsv }
  }
} else { Write-Host "   (missing $fs - skipping baseline; run data\scripts\gen_weights.py to generate the static schema)" }

# ============================ [8] Summary ==================================
Write-Host ""; Write-Host "==== TABLE 1: Peak memory (peak heap MB, mean +/- sd) ===="
$memAgg.Keys | Sort-Object | ForEach-Object { "  {0,-34} {1}" -f $_, (Stat $memAgg[$_]) }
if ($doOOM) {
  Write-Host ""; Write-Host "==== OOM (NCI1 5%/0.3 @2g) ===="
  Import-Csv "$OUT\oom.csv" | Group-Object algo,status | ForEach-Object { "  {0}: {1} times" -f $_.Name, $_.Count }
}
Write-Host ""; Write-Host "==== TABLE 2: Warm timing (ms, mean +/- sd) ===="
$timeAgg.Keys | Sort-Object | ForEach-Object { "  {0,-34} {1}" -f $_, (Stat $timeAgg[$_]) }
Write-Host ""; Write-Host "==== TABLE 3: Phase breakdown (matching %) ===="
Import-Csv "$phCsv" | ForEach-Object { "  {0} s={1} t={2}  total={3}  matching={4} ({5}%)" -f $_.dataset,$_.sigma,$_.tau,$_.total_ms,$_.matching,$_.matching_pct }
Write-Host ""; Write-Host "==== TABLE 4: MUTAG sensitivity (memory reduction and speedup) ===="
Import-Csv "$sCsv" | ForEach-Object { "  sigma={0} tau={1}  #patterns={2}  mem_reduction={3}x  speedup={4}x" -f $_.sigma,$_.tau,$_.patterns,$_.mem_reduction,$_.speedup }
Write-Host ""; Write-Host "==== TABLE 5: Ablation (ms, mean +/- sd) ===="
$aAgg.Keys | Sort-Object | ForEach-Object { "  {0,-40} {1}" -f $_, (Stat $aAgg[$_]) }
if (Test-Path $blCsv) {
  Write-Host ""; Write-Host "==== section 5.8 Published baselines (MUTAG static): peak heap ===="
  Import-Csv "$blCsv" | ForEach-Object { "  {0,-13} {1,-16} peak={2}MB  #patterns={3}" -f $_.algo,$_.threshold,$_.peak_heap_mb,$_.patterns }
}
Write-Host ""; Write-Host "==== Pruning stats (pivot): candidates/prefiltered/nonCanonical/evaluated ===="
$pruneAgg.Keys | Sort-Object | ForEach-Object { "  {0,-26} {1}" -f $_, $pruneAgg[$_] }
Write-Host ""; Write-Host "DONE. Raw CSV: $OUT\  | Your numbers are the reference for this machine."
