# Architecture

Pivot-WFSM mines weighted frequent subgraphs from a transactional multigraph
database. It follows the level-wise, canonical-DFS-code framework of gSpan, but
replaces the usual **embedding-store** evaluation with **on-demand re-matching**:
no pattern carries its embedding lists; each candidate is re-checked for
existence when needed, using a pivot-anchored subgraph-isomorphism test. Peak
memory therefore scales with a single embedding being probed rather than with
the total number of embeddings.

## Module map

```
src/main/java/pivotwfsm/
├── core/       Graph and pattern types: MultiGraph, MultiGraphDB (streaming JSON
│               loader), Pattern, DFSCode, MinDFSCode, Embedding.
├── pivot/      The contribution. PivotSelector (choose the pivot vertex),
│               PivotSignature + PivotCandidateFilter (necessary-condition filter),
│               HostGraphIndex (adjacency index built once), PivotMatcher
│               (pivot-anchored existence check), PivotExtensionGenerator,
│               PivotWfsmMiner (the mining loop).
├── miner/      Shared mining scaffolding and EmbeddingStoreMiner, the embedding-store
│               baseline (same measure and canonical check as the pivot miner),
│               plus the right-most-extension and canonical-code machinery.
├── baselines/  Published algorithms for context comparison: gSpan, JCZ-ATW,
│               MaxW-gSpan, WFSM-MaxPWS, DewgSpan.
├── weight/     Edge-weight aggregators: MIN (bottleneck), AVG, MAX.
└── cli/        SingleRun and PhaseBreakdown - one run per JVM, one CSV row out.
```

The isolated comparison in the paper is `pivot` (PivotWfsmMiner) vs `embed-min`
(EmbeddingStoreMiner with the MIN measure): identical database representation, measure,
prefilter, and canonical check, so the *only* difference is the matching
strategy. Both emit the same pattern set.

## Dataset format

All datasets - the small `data/sample/illustrative.json`, benchmarks converted
from TUDataset, and the weighted variants - use one JSON schema:

```json
{
  "name": "illustrative",
  "label_strings": ["A", "B", "C", "D"],
  "graphs": [
    {
      "id": 0,
      "vertices": [ {"id": 0, "label": 0}, {"id": 1, "label": 1} ],
      "edges":    [ {"src": 0, "dst": 1, "weight": 0.85} ]
    }
  ]
}
```

- `label_strings` (optional) maps integer vertex labels back to symbols.
- Vertices are listed in `id` order (`vertices[k].id == k`); labels are
  non-negative integers.
- Multiple edges may share the same `(src, dst)` pair - that is the multigraph
  case. Edge weights lie in `(0, 1]`.

The loader (`MultiGraphDB.loadJson`) parses this with a streaming pull-parser so
that large files (tens of thousands of graphs, hundreds of MB) do not build a
full in-memory DOM.

## Notation

| Symbol | Meaning |
|--------|---------|
| `D = {G_1, ..., G_N}` | transactional database of `N` weighted multigraphs |
| `g`, `p` | a pattern; the pivot vertex chosen inside it |
| `PS(p)` | pivot signature (label, degree, neighbour-label multiset, ...) |
| `τ_w` | edge-weight threshold; an embedding is valid only if every mapped edge has weight ≥ τ_w |
| `σ_min` | minimum support (number of graphs that contain the pattern) |
| `Embeds(g)` | set of graph indices that support `g` (not the embeddings themselves) |

## How the pieces fit

1. `HostGraphIndex` builds one adjacency index over the whole database.
2. `InitialPatterns` produces the frequent single-edge patterns.
3. The mining loop pops a pattern, generates right-most extensions, drops
   non-canonical ones (`MinDFSCode`), then evaluates each candidate.
4. Evaluation is the key step: instead of extending the parent's embeddings,
   `PivotSelector` picks a pivot, `PivotCandidateFilter` prunes host vertices
   that cannot play the pivot (a proven necessary condition), and `PivotMatcher`
   runs a backtracking existence check that stops at the first valid embedding -
   only on the graphs that supported the parent.

See the paper for the correctness/completeness proofs, the necessary-condition
lemma for the filter, and the time/space complexity analysis.
