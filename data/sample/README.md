# `illustrative` - original demonstrative dataset

A 6-multigraph database designed from scratch for two purposes:

1. **Algorithm test fixture.** Small enough that every pattern can be
   hand-verified; rich enough to exercise the entire mining pipeline
   (multigraphs, weighted edges, branching extensions, pruning).
2. **Paper running example.** Concrete case where bottleneck-MIN
   and the average-based baselines (WFSM-MaxPWS, DewgSpan) return
   **different** pattern sets - the hedge promised in survey
   recommendation 4 / §134.

Not derived from MUTAG, PTC, NCI, or any other benchmark - so reusing this
example in the paper does not overlap with prior work.

## Files

| File | Purpose |
|---|---|
| `illustrative.json` | The 6 multigraphs. Schema below. |
| `ground_truth.json` | Pattern-level expected outputs, computed by hand. |
| `README.md` | This file. |

## Schema

```jsonc
{
  "name": "illustrative",
  "label_strings": ["A", "B", "C", "D"],     // mapping from int label → human name
  "graphs": [
    {
      "id": 0,
      "vertices": [
        {"id": 0, "label": 0},               // label is an index into label_strings
        ...
      ],
      "edges": [
        {"src": 0, "dst": 1, "weight": 0.85}, // multiple entries with same (src,dst)
        {"src": 0, "dst": 1, "weight": 0.40}, //  → parallel edges (multigraph!)
        ...
      ]
    }
  ]
}
```

## The 6 graphs at a glance

| Graph | \|V\| | \|E\| | Distinguishing structure |
|---|---|---|---|
| G0 | 4 | 5 | Two parallel A-B edges (0.85, 0.40) plus a B-D branch |
| G1 | 4 | 4 | Square-like A-B-C-D with B-D diagonal - every edge ≥ 0.55 |
| G2 | 5 | 5 | **Two B-labeled vertices** (v1, v4) - tests label disambiguation |
| G3 | 3 | 3 | Triangle A-B-C where A-B has weight 0.10 - the weak-link case |
| G4 | 4 | 5 | Square A-B-C-D with diagonal A-C |
| G5 | 4 | 5 | Two parallel A-B edges of contrasting strength (0.55, 0.95) |

Totals: 24 vertices, 27 edges.

## Patterns the dataset is built around

Five canonical patterns make up the running example. See
`ground_truth.json` for exact W_min / W_avg per host graph.

| Pattern | What | σ (transactional) | σ_MIN @ τ_w=0.5 | σ_AVG @ τ_w=0.5 |
|---|---|---|---|---|
| **P1** | single edge A-B | 6 | 5 | 5 |
| **P2** | path A-B-C | 6 | **5** | **6** |
| **P3** | path A-B-D | 3 | 3 | 3 |
| **P4** | triangle A-B-C | 2 | 2 | 2 |
| **P5** | star B → {A, C, D} | 3 | 3 | 3 |

**The MIN vs AVG divergence.** At sigma_min=6 and tau_w=0.5, pattern **P2**
(path A-B-C) is:

- **AVG-frequent**: every graph has W_avg(P2, G) ≥ 0.5. Even in G3, where
  A-B is severely weak at 0.10, the strong B-C(0.95) drags the average to
  0.525 - above τ_w.
- **MIN-rejected**: in G3, W_min(P2, G3) = min(0.10, 0.95) = 0.10 < 0.5,
  so G3 is not counted. σ_MIN(P2) = 5 < 6 → not frequent.

This is the exact phenomenon survey §134 warns about: AVG can mask a weak
link that MIN correctly surfaces.

## Hand calculations underlying `ground_truth.json`

### P1 - single edge (A, B)

Each graph's BEST embedding (max over edges with the right labels):

```
G0: max(0.85, 0.40)             = 0.85
G1: 0.90                         = 0.90
G2: max(0.75 via B0, 0.45 via B1)= 0.75
G3: 0.10                         = 0.10
G4: 0.65                         = 0.65
G5: max(0.55, 0.95)              = 0.95
```

At τ_w = 0.5: G3 fails (0.10 < 0.5) → σ_MIN(P1) = 5.

### P2 - path A-B-C

Within a graph, an embedding picks an A-B edge then a B-C edge sharing
the B endpoint. Bottleneck = min of the two weights. Best embedding =
max-over-choices of that bottleneck.

```
G0: (A-B 0.85, B-C 0.70)  → min 0.70   [best]
G1: (A-B 0.90, B-C 0.60)  → min 0.60
G2 via B0: (0.75, 0.85)   → 0.75       [best]
    via B1: (0.45, 0.95)  → 0.45
G3: (0.10, 0.95)          → 0.10
G4: (0.65, 0.80)          → 0.65
G5: (A-B 0.55, B-C 0.50)  → 0.50
    (A-B 0.95, B-C 0.50)  → 0.50       [best]
```

W_avg (for the chosen best embedding above):

```
G0: avg(0.85, 0.70) = 0.775
G1: avg(0.90, 0.60) = 0.75
G2: avg(0.75, 0.85) = 0.80
G3: avg(0.10, 0.95) = 0.525  ← AVG marginally accepts
G4: avg(0.65, 0.80) = 0.725
G5: avg(0.95, 0.50) = 0.725
```

At τ_w = 0.5:
- σ_MIN(P2) = |{G : W_min ≥ 0.5}| = 5  (G3 fails at 0.10)
- σ_AVG(P2) = |{G : W_avg ≥ 0.5}| = 6  (G3 just clears at 0.525)

### P3 - path A-B-D

```
G0: A-B (best 0.85) and B-D (only 0.50) → 0.50
G1: A-B 0.90  and B-D 0.55             → 0.55
G2: A-B0 0.75 and B0-D 0.70            → 0.70   [via B0; B1 has no D neighbour]
G3: no D vertex                         → fail
G4: no edge B-D (B has only A and C as neighbours) → fail
G5: no edge B-D                          → fail
```

σ(P3) = 3.

### P4 - triangle A-B-C

Needs edges A-B, B-C, **and** A-C all on the same triple of vertices.

```
G0: A-C edge absent              → fail
G1: A-C edge absent              → fail
G2: A-C edge absent              → fail
G3: A-B 0.10, B-C 0.95, A-C 0.60 → min 0.10
G4: A-B 0.65, B-C 0.80, A-C 0.40 → min 0.40
G5: A-C edge absent              → fail
```

σ(P4) = 2. Useful for testing that the miner correctly drops patterns
whose transactional support falls below σ_min, regardless of how heavy
their realised edges are.

### P5 - star with B at the centre

B must be adjacent to one A, one C, and one D - three distinct neighbours.

```
G0: B (v1) → A(v0) 0.85, C(v2) 0.70, D(v3) 0.50 → min 0.50
G1: B (v1) → A 0.90, C 0.60, D 0.55             → min 0.55
G2: B0 (v1) → A 0.75, C 0.85, D 0.70            → min 0.70
G3: no D vertex                                  → fail
G4: B (v1) → A 0.65, C 0.80, no D neighbour      → fail
G5: B (v1) → A (0.55 or 0.95), C 0.50, no D-neighbour → fail
```

σ(P5) = 3.
