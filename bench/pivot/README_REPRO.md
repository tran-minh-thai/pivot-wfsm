# Reproducing the Pivot-WFSM experiments

These scripts regenerate every number behind the paper on your own machine.
Absolute memory values are machine- and GC-dependent, so treat the committed
figures as reference points - run the commands below to get your own. The
**ratios** (memory reduction, the time cross-over, matching as the bottleneck,
the natural OOM at scale) hold regardless of the absolute numbers.

The main run covers eight measurement groups, one per paper table:
`[1]` peak memory · `[2]` OOM · `[3]` warm runtime · `[4]` phase breakdown ·
`[5]` σ×τ sensitivity · `[6]` 2×2 ablation · `[7]` published baselines ·
`[8]` search-space counters. Two further scripts add the large-scale sweep and
the memory cross-check.

## 0. Prerequisites

- JDK 21 and Maven 3.8+ (`java -version`, `mvn -version`).
- Python 3.10+ with `numpy` and `pyyaml` (only needed to (re)generate data).
- ~5 GB disk for data; ~4 GB RAM for the main run (the OOM demo deliberately
  caps the heap at 2 GB), and ~12 GB RAM for the 8 GB large-scale sweep.
- Windows (PowerShell), macOS, or Linux - the `.ps1` and `.sh` scripts measure
  the same set of configurations.

**Memory metric.** Reported memory is *peak live heap*: the maximum heap
occupancy sampled right after a garbage collection, read from GC notifications
(no forced GC, so timing is not perturbed). This is the quantity that peak-RSS
captures for a non-GC implementation. Raw "used heap" is not used as the headline
because under a managed runtime it also counts uncollected garbage and therefore
tracks `-Xmx` and GC policy rather than the algorithm.

The workflow is **two-phase**: phase 1 needs the network (download data, cache
Maven dependencies); phase 2 is purely local and can run **offline** for a clean
measurement environment.

## 1. Prepare (online, once)

Cache all Maven dependencies (so phase 2 runs offline), download TUDataset, and
generate edge weights:

```bash
# macOS / Linux
bash bench/pivot/prepare.sh
```
```powershell
# Windows
pwsh -ExecutionPolicy Bypass -File bench\pivot\prepare.ps1
```

Needs `java`, `mvn`, and `python3` (macOS/Linux) / `python` (Windows) on PATH.
Takes ~20–40 minutes, mostly downloading and generating ~1 GB of data. It prints
the phase-2 command when done.

## 2. Measure (offline)

After phase 1 you may disconnect the network. The scripts use `mvn -o` (offline)
against the cached dependencies and download nothing:

```bash
# macOS / Linux
bash bench/pivot/reproduce.sh quick    # MUTAG/PTC, 2 seeds, ~10 min
bash bench/pivot/reproduce.sh full     # + NCI1/NCI109, 5 seeds, OOM, baselines
```
```powershell
# Windows
pwsh -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 quick
pwsh -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 full
```

Prefer `pwsh` (PowerShell 7) over `powershell` (5.1); the scripts are pure ASCII
so both work. If execution is blocked: `Set-ExecutionPolicy -Scope Process
Bypass`. If phase 1 has not been run, the script stops with a clear message.

The scripts warm the JVM (2 warm-up + 5 timed runs) before timing, and report
mean ± standard deviation. The `.ps1` and `.sh` variants measure the identical
configurations.

## 3. Large-scale sweep and cross-check

Database-size scalability on Yeast (up to all 79,601 graphs) and the natural OOM:

```bash
bash bench/pivot/prepare_scale.sh      # download Yeast + NCI-H23, subsample by size
SCALE_HEAP=8g bash bench/pivot/reproduce_scale.sh
```

Independent memory cross-check - the smallest `-Xmx` at which each method
completes (binary search over a heap ladder):

```bash
bash bench/pivot/minheap.sh main       # the four standard datasets
bash bench/pivot/minheap.sh scale      # min-heap vs database size on Yeast
```

## 3b. Filling one gap without re-running everything

`reproduce.*` measures every configuration the paper reports, so a full run is
the simplest way to refresh the numbers. When only one configuration is missing,
this script measures PTC-MR at tauW=0.5 on its own, with the same protocol, JVM
flags and CSV columns:

```bash
bash bench/pivot/extra_ptcmr_tau05.sh                                  # macOS/Linux
powershell -ExecutionPolicy Bypass -File bench\pivot\extra_ptcmr_tau05.ps1   # Windows
```

A second top-up re-measures the NCI1 ablation over all five seeds. It used to run
on two, while every other configuration used five, so its row carried a standard
deviation over n=2:

```bash
bash bench/pivot/extra_nci1_ablation.sh                                  # macOS/Linux
powershell -ExecutionPolicy Bypass -File bench\pivot\extra_nci1_ablation.ps1   # Windows
```

Every normal-distribution dataset is measured at tauW=0.5; PTC-MR is reported at
0.5 and at 0.3 because the weight threshold moves the result in both directions:
0.3 admits many more patterns, so the embedding store grows and the memory
advantage widens, while the runtime advantage narrows. Output goes to
`results/pivot/extra/`.

## 4. Output

- Raw CSVs land in `results/pivot/repro/` (main run),
  `results/pivot/extra/` (the PTC-MR tauW=0.5 top-up),
  `results/pivot/scale/` and `results/pivot/minheap/`.
- Aggregated tables print at the end of each run; re-print them on macOS/Linux
  with `bash bench/pivot/aggregate.sh results/pivot/repro` and
  `bash bench/pivot/aggregate_scale.sh results/pivot/scale`.
- The committed reference set and its mapping to the paper tables and figures are
  in [`../../results/pivot/README.md`](../../results/pivot/README.md).

## 5. Reading the numbers

- The matching `patterns` column between `pivot` and `embed-min` confirms the
  two produce the same pattern set - the comparison is isolated and varies only
  the matching strategy.
- `prefiltered ≈ 0` on the main configurations: as the ablation shows, the
  optional label-pair prefilter contributes little once the pivot signature and
  parent-support inheritance are in place; the advantage comes from the matching
  strategy.
- The heavy-tailed `nexp` distribution yields a smaller memory ratio, because the
  output (and therefore the embedding store) is small there to begin with.
- Run **sequentially**; parallel runs contend for memory and CPU and pollute the
  measurements.
