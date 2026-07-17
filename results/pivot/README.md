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
| `mem.csv` | Table 1 | Peak live heap: 4 datasets under `normal`, plus MUTAG and NCI1 under `nexp` |
| `time.csv` | Table 2 | Warm-JVM runtime in the selective-threshold regime |
| `phases.csv` | Table 3 | Per-phase time breakdown (matching dominates) |
| `sensitivity.csv` | Table 4 | Memory reduction and speed-up vs support threshold on MUTAG |
| `ablation.csv` | Table 5 | 2×2 ablation: pivot vs plain matching × prefilter on/off |
| `baselines.csv` | §5.8 | Published baselines (gSpan, WFSM-MaxPWS, DewgSpan) memory |
| `oom.csv` | §OOM | NCI1 at a 2 GB heap: Pivot-WFSM completes, embedding store runs out of memory |
| `scale.csv` | Scale figure | Peak live heap vs database size on Yeast, up to all 79,601 graphs (8 GB heap) |
| `minheap.csv` | §Setup cross-check | Smallest `-Xmx` at which each method completes (binary search) |
| `prune.csv` | - | Search-space counters for the pivot variant (candidates / prefiltered / non-canonical / evaluated) |

## Seeds, and one gap

Every configuration is measured over the same five weight seeds (42, 1337, 2024,
31415, 271828), except:

- **Yeast** (`scale.csv`, and the scale mode of `minheap.csv`) uses three, because
  of its running cost. The paper states this.
- **NCI1 in `ablation.csv` still has only two.** The scripts no longer do this -
  the exception was removed and the run costs about a minute - but the rows here
  predate the fix. Re-measure with
  `bench/pivot/extra_nci1_ablation.{sh,ps1}` and replace those rows. Until then,
  the standard deviation on that one row is computed over n=2 and means little.

## Weight threshold

Every `normal` dataset is measured at tauW=0.5. PTC-MR is additionally measured
at 0.3, and MUTAG at a lower support with 0.3, because the weight threshold moves
the two metrics in opposite directions: a lower threshold admits more patterns,
which grows the embedding store (widening the memory advantage) while giving it
more work to amortise (narrowing the runtime advantage).

The PTC-MR tauW=0.5 rows were produced by `extra_ptcmr_tau05.ps1` on the same
machine, with the same protocol and JVM flags, in a separate session from the
rest of the set.

Column meanings are documented in the CLI that emits them,
[`SingleRun.java`](../../src/main/java/pivotwfsm/cli/SingleRun.java).

`pivot` = Pivot-WFSM (on-demand re-matching). `embed-min` = the embedding-store
baseline sharing the same core, measure, and canonical check, so the only
difference is the matching strategy; both produce the identical pattern set,
confirmed by the matching `patterns` column in every row.
