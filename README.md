# Pivot-WFSM

**Memory-scalable weighted frequent subgraph mining on transactional multigraph
databases, via on-demand re-matching anchored at a pivot vertex.**

Algorithms in the gSpan family evaluate candidate patterns by storing, for every
live pattern, the full list of its embeddings on every database graph. That is
fast, but peak memory grows with the *number of embeddings* and blows up exactly
where users need low thresholds or large databases. Pivot-WFSM replaces the
embedding store with **on-demand re-matching**: no pattern keeps embeddings; each
candidate is re-checked for existence when needed, anchored at a structurally
chosen pivot vertex and pruned by a local signature. Peak memory then scales with
a single embedding being probed instead of with all embeddings.

On the standard benchmarks this cuts peak live heap by **3.5–68×** versus an
embedding-store baseline that produces the *identical* pattern set - the widest
gaps at the low thresholds and large databases where the embedding store swells,
the narrowest where it stays small and memory is not the binding constraint. In
the selective-threshold regime Pivot-WFSM is also **2.5–10×** faster, and it
completes on the full Yeast database (79,601 graphs) at a memory budget where the
embedding store runs out of memory.

## Layout

```
src/main/java/pivotwfsm/
├── core/        graph / pattern / DFS-code types (MultiGraph, MinDFSCode, ...)
├── pivot/       the contribution: pivot selection, signature, matcher, miner
├── miner/       mining framework + EmbeddingStoreMiner (embedding-store baseline) + prefilter
├── baselines/   gSpan, JCZ-ATW, WFSM-MaxPWS, DewgSpan (published methods, for context)
├── weight/      edge-weight aggregators (MIN / AVG / MAX)
└── cli/         SingleRun, PhaseBreakdown (experiment runners)
bench/pivot/     reproduction scripts (prepare / reproduce, sh + ps1)
data/scripts/    TUDataset download + weighting + subsampling pipeline
data/sample/     tiny illustrative database used by the loader tests
results/pivot/   reference measurements, mapped to the paper (see its README)
docs/            ARCHITECTURE.md - module map, data format, notation
```

`EmbeddingStoreMiner` (in `miner/`) is the **embedding-store baseline** under the same MIN
(bottleneck) measure - the isolated comparison that varies only the matching
strategy.

## Build and test

Requires JDK 21 and Maven.

```bash
mvn test          # 135 tests, including parity with the embedding-store baseline
mvn -q package    # builds target/pivotwfsm.jar (self-contained, runnable)
```

Mine the tiny bundled example to check the build works. Run the jar with no
arguments to see every algorithm, parameter and output column:

```bash
java -jar target/pivotwfsm.jar                                    # usage
java -jar target/pivotwfsm.jar pivot data/sample/illustrative.json 0.5 0.5
```

## Reproduce the experiments

Two phases: an online phase that downloads data and caches Maven dependencies,
and an offline phase that runs the measurements. See
[`bench/pivot/README_REPRO.md`](bench/pivot/README_REPRO.md) for full detail.

```bash
# macOS / Linux
bash bench/pivot/prepare.sh          # online: download TUDataset, generate weights
bash bench/pivot/reproduce.sh full   # offline: memory, OOM, time, phases, ablation, baselines

# Windows
powershell -ExecutionPolicy Bypass -File bench\pivot\prepare.ps1
powershell -ExecutionPolicy Bypass -File bench\pivot\reproduce.ps1 full
```

Large-scale sweep on Yeast (database-size scalability and natural OOM):

```bash
bash bench/pivot/prepare_scale.sh      # download Yeast + NCI-H23, subsample by size
bash bench/pivot/reproduce_scale.sh    # peak live heap vs N; embedding store OOMs at full scale
```

Run a single configuration directly:

```bash
java -cp "target/classes:$(mvn -o -q -Dmdep.outputFile=/dev/stdout \
     dependency:build-classpath)" \
     pivotwfsm.cli.SingleRun pivot data/weighted/per_instance/normal/NCI1.normal.s42.json 0.10 0.5
```

Reference results and the CSV-to-paper-table mapping are in
[`results/pivot/README.md`](results/pivot/README.md).

## A note on the memory metric

Memory is reported as **peak live heap** - the maximum heap occupancy measured
right after a garbage collection. Under a managed runtime, raw "used heap" also
counts uncollected garbage, so it depends on `-Xmx` and GC policy rather than on
the algorithm, and it penalises the short-lived-allocation pattern of re-matching.
Peak live heap is what peak-RSS captures for a non-GC implementation. Every
result is cross-checked against an independent quantity, the smallest `-Xmx` at
which each method completes (`bench/pivot/minheap.*`), and the two agree.
