# Reference results

Raw measurements backing the tables and figures in the paper. All values were
produced by the reproduction scripts in [`bench/pivot/`](../../bench/pivot);
running them on another machine regenerates these files (absolute memory values
are machine- and GC-dependent, so expect small differences - the *ratios* are
the comparable quantities).

Memory is reported as **peak live heap**: the maximum heap occupancy sampled
right after a garbage collection (via GC notifications). This is the quantity
that peak-RSS captures for a non-GC implementation; raw "used heap" is not used
because it also counts uncollected garbage and therefore depends on the heap
size and GC policy rather than on the algorithm.

| File | Backs | Content |
|------|-------|---------|
| `mem.csv` | Table 2 | Peak live heap, 5 seeds: 4 datasets under `normal`, plus MUTAG and NCI1 under `nexp` |
| `time.csv` | Table 3 | Warm-JVM runtime in the selective-threshold regime |
| `phases.csv` | Table 4 | Per-phase time breakdown (matching dominates) |
| `sensitivity.csv` | Table 5 | Memory reduction and speed-up vs support threshold on MUTAG |
| `ablation.csv` | Table 6 | 2×2 ablation: pivot vs plain matching × prefilter on/off |
| `baselines.csv` | §5.9 | Published baselines (gSpan, WFSM-MaxPWS, DewgSpan) memory |
| `oom.csv` | §5.3 | NCI1 at a 2 GB heap: Pivot-WFSM completes, embedding store runs out of memory |
| `scale.csv` | Figure 3 (§5.4) | Peak live heap vs database size on Yeast, up to all 79,601 graphs (8 GB heap) |
| `minheap.csv` | §5.1 cross-check | Smallest `-Xmx` at which each method completes (binary search) |
| `prune.csv` | - | Search-space counters for the pivot variant (candidates / prefiltered / non-canonical / evaluated) |
| `extra/` | §5.2 note | Supplementary PTC-MR run at tau=0.5 backing the configuration choice (few patterns, little memory to save) |

Column meanings are documented in the CLI that emits them,
[`SingleRun.java`](../../src/main/java/pivotwfsm/cli/SingleRun.java).

`pivot` = Pivot-WFSM (on-demand re-matching). `embed-min` = the embedding-store
baseline sharing the same core, measure, and canonical check, so the only
difference is the matching strategy; both produce the identical pattern set,
confirmed by the matching `patterns` column in every row.
