"""Build GENUINE transactional multigraph databases from TUDataset chemistry data.

WHY THIS SCRIPT EXISTS
----------------------
The miners here target *multigraphs*, but the TUDataset chemistry sets
normally used (MUTAG, PTC_MR, NCI1, NCI109) are strictly SIMPLE graphs:
zero parallel edges, max edge multiplicity 1. Their edges also carry no
labels at all, only weights, so the edge-label alphabet Sigma_E in the
complexity analysis is empty in practice.

This script fixes both problems at the source, by exploiting the fact that
a chemical bond order is *inherently* a multiplicity:

    single bond    -> 1 edge
    double bond    -> 2 parallel edges
    triple bond    -> 3 parallel edges
    aromatic bond  -> configurable (default 1; see AROMATIC_MULTIPLICITY)

This is not a synthetic trick. It is the standard multigraph rendering of a
molecule, and it is exactly the semantics the paper's motivation appeals to:
a substructure is stable only when its WEAKEST bond exceeds the dissociation
threshold, and a double bond is physically two shared electron pairs.

DATASETS THAT WORK
------------------
Only datasets that ship <NAME>_edge_labels.txt can be converted. Verified
against the official TUDataset table (https://chrsmrrs.github.io/datasets/):

    MUTAG    edge labels: YES  (188 graphs)
    PTC_MR   edge labels: YES  (344 graphs)
    MCF-7    edge labels: YES  (27,770 graphs)   <- used by Islam et al. 2024
    P388     edge labels: YES  (41,472 graphs)   <- used by Islam et al. 2024
    Yeast    edge labels: YES  (79,601 graphs)   <- used by Islam et al. 2024

    NCI1     edge labels: NO   -> CANNOT be made a multigraph this way
    NCI109   edge labels: NO   -> CANNOT be made a multigraph this way
    PROTEINS edge labels: NO
    IMDB-BINARY edge labels: NO

Recommendation: add MCF-7, P388 and Yeast. They are the datasets the
baseline paper (Islam et al., Applied Intelligence 54(5):3756-3785, 2024)
evaluates on, so using them gives both a genuine multigraph setting AND a
head-to-head comparison on the baselines' own benchmarks.

BOND-LABEL MAPPING -- READ THIS
-------------------------------
TUDataset stores edge labels as opaque integers. The integer -> bond-order
mapping is NOT documented uniformly across datasets. This script therefore
does NOT guess silently: it prints the observed label distribution and the
mapping it is about to apply, and refuses to run unless the mapping covers
every observed label. Verify the printed distribution against the chemistry
before trusting the output. For MUTAG the widely used convention is:

    0 = aromatic, 1 = single, 2 = double, 3 = triple

Override with --bond-map if your dataset differs, e.g.
    --bond-map "0:1,1:1,2:2,3:3"

USAGE
-----
    python data/scripts/make_multigraph.py --datasets MUTAG PTC_MR
    python data/scripts/make_multigraph.py --datasets MUTAG --aromatic 2
    python data/scripts/make_multigraph.py --datasets MUTAG --dry-run

Output: data/raw/<NAME>.multigraph.unweighted.json, same schema as the
existing *.unweighted.json, but edges may repeat a vertex pair and each edge
carries a "label" field. Feed it to gen_weights.py exactly as before.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = PROJECT_ROOT / "data" / "raw"

# Datasets that TUDataset ships with an edge-label file. Anything else cannot
# be turned into a multigraph by bond-order expansion.
EDGE_LABELLED = {"MUTAG", "PTC_MR", "PTC_FM", "PTC_FR", "PTC_MM",
                 "MCF-7", "P388", "Yeast", "Mutagenicity", "AIDS", "DBLP_v1"}

# Per-dataset edge-label encoding, taken from each dataset's official TUDataset
# README (https://chrsmrrs.github.io/datasets/). The integer -> bond meaning is
# NOT consistent across datasets (e.g. MUTAG's label 0 is aromatic, PTC_MR's is
# triple), so each is recorded explicitly as:  label -> (bond name, multiplicity).
# Bond order IS the multiplicity: single=1, double=2, triple=3. Aromatic has no
# integer order and defaults to 1 (override with --aromatic).
KNOWN_ENCODINGS: dict[str, dict[int, tuple[str, int]]] = {
    # MUTAG ships no README; uses the widely cited Debnath et al. (1991) order.
    "MUTAG":  {0: ("aromatic", 1), 1: ("single", 1), 2: ("double", 2), 3: ("triple", 3)},
    # PTC_* READMEs state: 0 triple, 1 double, 2 single, 3 aromatic.
    "PTC_MR": {0: ("triple", 3), 1: ("double", 2), 2: ("single", 1), 3: ("aromatic", 1)},
    "PTC_MM": {0: ("triple", 3), 1: ("double", 2), 2: ("single", 1), 3: ("aromatic", 1)},
    "PTC_FM": {0: ("triple", 3), 1: ("double", 2), 2: ("single", 1), 3: ("aromatic", 1)},
    "PTC_FR": {0: ("triple", 3), 1: ("double", 2), 2: ("single", 1), 3: ("aromatic", 1)},
}

# Used only when a dataset is not in KNOWN_ENCODINGS. It is the MUTAG order; the
# script prints it and warns so the user can verify it against the chemistry.
FALLBACK_ENCODING = {0: ("aromatic", 1), 1: ("single", 1),
                     2: ("double", 2), 3: ("triple", 3)}


def read_ints(path: Path) -> list[int]:
    with path.open(encoding="utf-8") as fh:
        return [int(s) for s in (ln.strip() for ln in fh) if s]


def read_edges(path: Path) -> list[tuple[int, int]]:
    out = []
    with path.open(encoding="utf-8") as fh:
        for ln in fh:
            s = ln.strip()
            if not s:
                continue
            u, v = s.split(",")
            out.append((int(u), int(v)))
    return out


def resolve_encoding(name: str) -> tuple[dict[int, tuple[str, int]], bool]:
    """Return (encoding, known) for a dataset; encoding maps label -> (name, mult)."""
    if name in KNOWN_ENCODINGS:
        return KNOWN_ENCODINGS[name], True
    return FALLBACK_ENCODING, False


def resolve_bond_map(name: str, spec: str | None,
                     aromatic: int | None) -> tuple[dict[int, int], dict[int, str]]:
    """Resolve the label -> multiplicity map and label -> name for one dataset.

    An explicit --bond-map wins. Otherwise the dataset's registered encoding is
    used, so the same run can convert datasets that disagree on the integer code
    (e.g. MUTAG and PTC_MR) without silently applying the wrong chemistry.
    """
    encoding, known = resolve_encoding(name)
    names = {lab: nm for lab, (nm, _) in encoding.items()}
    if spec:
        bond_map = {}
        for part in spec.split(","):
            k, v = part.split(":")
            bond_map[int(k)] = int(v)
        return bond_map, names
    bond_map = {lab: mult for lab, (_, mult) in encoding.items()}
    if aromatic is not None:
        for lab, nm in names.items():
            if nm == "aromatic":
                bond_map[lab] = aromatic
    return bond_map, names


def build(name: str, spec: str | None, aromatic: int | None, dry_run: bool) -> None:
    bond_map, names = resolve_bond_map(name, spec, aromatic)
    _, known = resolve_encoding(name)
    src_note = "explicit --bond-map" if spec else (
        "registered README encoding" if known
        else "FALLBACK (MUTAG) encoding -- VERIFY against this dataset's chemistry")
    print(f"[{name}] bond map (label -> multiplicity): {bond_map}  [{src_note}]")

    src = RAW_DIR / name
    if not src.is_dir():
        sys.exit(f"[{name}] missing {src} -- run download_tudataset.py first")

    edge_label_file = src / f"{name}_edge_labels.txt"
    if not edge_label_file.exists():
        sys.exit(
            f"[{name}] no {name}_edge_labels.txt.\n"
            f"  This dataset has NO edge labels, so bond order is unavailable and\n"
            f"  it CANNOT be converted into a multigraph. Per the official TUDataset\n"
            f"  table, NCI1/NCI109/PROTEINS/IMDB-BINARY fall in this category.\n"
            f"  Use MUTAG, PTC_MR, MCF-7, P388 or Yeast instead.\n"
            f"  (If you have just re-downloaded, note that download_tudataset.py\n"
            f"   never extracted *_edge_labels.txt -- fetch the zip again.)"
        )

    edges = read_edges(src / f"{name}_A.txt")            # 1-indexed, both directions
    elabels = read_ints(edge_label_file)                  # one per line of _A.txt
    indicator = read_ints(src / f"{name}_graph_indicator.txt")
    nlabels = read_ints(src / f"{name}_node_labels.txt")

    if len(edges) != len(elabels):
        sys.exit(f"[{name}] _A.txt has {len(edges)} lines but "
                 f"_edge_labels.txt has {len(elabels)} -- misaligned files")

    dist = Counter(elabels)
    print(f"\n[{name}] observed edge labels (directed-arc counts):")
    for lab, cnt in sorted(dist.items()):
        nm = names.get(lab, "?")
        mult = bond_map.get(lab)
        mark = f"-> multiplicity {mult}" if mult is not None else "-> NOT MAPPED"
        print(f"    label {lab:>2} ({nm:>8}): {cnt:>8} arcs  {mark}")

    unmapped = set(dist) - set(bond_map)
    if unmapped:
        sys.exit(f"[{name}] labels {sorted(unmapped)} have no multiplicity in the "
                 f"bond map. Pass --bond-map explicitly, e.g. \"0:1,1:1,2:2,3:3\".")

    # Group into graphs. _A.txt lists each undirected edge twice (u,v) and (v,u);
    # keep one direction only, so each chemical bond is counted once.
    v2g = {i + 1: g for i, g in enumerate(indicator)}
    per_graph_edges: dict[int, list[tuple[int, int, int]]] = defaultdict(list)
    for (u, v), lab in zip(edges, elabels):
        if u >= v:
            continue                      # canonical direction, drops the mirror arc
        per_graph_edges[v2g[u]].append((u, v, lab))

    per_graph_vertices: dict[int, list[int]] = defaultdict(list)
    for i, g in enumerate(indicator):
        per_graph_vertices[g].append(i + 1)

    graphs = []
    total_edges = 0
    total_bonds = 0
    par_pairs = 0
    mult_hist: Counter = Counter()

    for gid in sorted(per_graph_vertices):
        verts = per_graph_vertices[gid]
        remap = {gv: i for i, gv in enumerate(sorted(verts))}
        out_edges = []
        for (u, v, lab) in per_graph_edges.get(gid, []):
            m = bond_map[lab]
            total_bonds += 1
            mult_hist[m] += 1
            if m > 1:
                par_pairs += 1
            # Expand the bond into m parallel edges, all sharing the bond label.
            for _ in range(m):
                out_edges.append({
                    "src": remap[u],
                    "dst": remap[v],
                    "label": lab,
                    "weight": 1.0,        # placeholder; gen_weights.py overwrites
                })
        total_edges += len(out_edges)
        graphs.append({
            "id": gid - 1,
            "vertices": [{"id": remap[gv], "label": nlabels[gv - 1]}
                         for gv in sorted(verts)],
            "edges": out_edges,
        })

    print(f"[{name}] {len(graphs)} graphs | {total_bonds} bonds -> "
          f"{total_edges} edges after expansion")
    print(f"[{name}] bonds carrying parallel edges: {par_pairs} "
          f"({100.0 * par_pairs / max(total_bonds, 1):.1f}% of all bonds)")
    print(f"[{name}] multiplicity histogram: "
          f"{dict(sorted(mult_hist.items()))}")
    print(f"[{name}] distinct edge labels |Sigma_E| = {len(dist)}")

    if par_pairs == 0:
        print(f"[{name}] WARNING: expansion produced NO parallel edges. "
              f"The result is still a simple graph -- check the bond map.")

    if dry_run:
        print(f"[{name}] --dry-run: nothing written")
        return

    out = RAW_DIR / f"{name}.multigraph.unweighted.json"
    with out.open("w", encoding="utf-8") as fh:
        json.dump({"name": name, "multigraph": True,
                   "bond_map": {str(k): v for k, v in bond_map.items()},
                   "graphs": graphs}, fh)
    print(f"[{name}] wrote {out.relative_to(PROJECT_ROOT)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datasets", nargs="+", default=["MUTAG", "PTC_MR"],
                    help="datasets to convert (must have _edge_labels.txt)")
    ap.add_argument("--bond-map", default=None,
                    help='explicit "label:multiplicity" map (applies to ALL '
                         '--datasets), e.g. "0:1,1:1,2:2,3:3". Omit to use each '
                         "dataset's registered README encoding.")
    ap.add_argument("--aromatic", type=int, default=None,
                    help="override multiplicity for the aromatic bond; default 1")
    ap.add_argument("--dry-run", action="store_true",
                    help="report statistics without writing files")
    args = ap.parse_args()

    if args.bond_map and len(args.datasets) > 1:
        print("WARNING: --bond-map is applied to every dataset, but the integer "
              "-> bond encoding differs across datasets (e.g. MUTAG vs PTC_MR). "
              "Convert them in separate runs, or omit --bond-map to use each "
              "dataset's registered encoding.")

    for name in args.datasets:
        if name not in EDGE_LABELLED:
            print(f"\n[{name}] NOTE: not in the known edge-labelled list "
                  f"{sorted(EDGE_LABELLED)}; attempting anyway.")
        build(name, args.bond_map, args.aromatic, args.dry_run)


if __name__ == "__main__":
    main()
