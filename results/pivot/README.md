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
| `mem.csv` | Table 1 | Peak live heap, 4 datasets × 2 weight distributions, 5 seeds |
| `time.csv` | Table 2 | Warm-JVM runtime in the selective-threshold regime |
| `phases.csv` | Table 3 | Per-phase time breakdown (matching dominates) |
| `sensitivity.csv` | Table 4 | Memory reduction and speed-up vs support threshold on MUTAG |
| `ablation.csv` | Table 5 | 2×2 ablation: pivot vs plain matching × prefilter on/off |
| `baselines.csv` | §5.8 | Published baselines (gSpan, WFSM-MaxPWS, DewgSpan) memory |
| `oom.csv` | §OOM | NCI1 at a 2 GB heap: Pivot-WFSM completes, embedding store runs out of memory |
| `scale.csv` | Scale figure | Peak live heap vs database size on Yeast, up to all 79,601 graphs (8 GB heap) |
| `minheap.csv` | §Setup cross-check | Smallest `-Xmx` at which each method completes (binary search) |
| `prune.csv` | - | Search-space counters for the pivot variant (candidates / prefiltered / non-canonical / evaluated) |

Column meanings are documented in the CLI that emits them,
[`SingleRun.java`](../../src/main/java/pivotwfsm/cli/SingleRun.java).

`pivot` = Pivot-WFSM (on-demand re-matching). `embed-min` = the embedding-store
baseline sharing the same core, measure, and canonical check, so the only
difference is the matching strategy; both produce the identical pattern set,
confirmed by the matching `patterns` column in every row.
