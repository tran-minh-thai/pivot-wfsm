"""Convert TUDataset raw text format into the project's common JSON.

Input (under ``data/raw/<NAME>/``):
    <NAME>_A.txt                 "src,dst" one edge per line, 1-indexed
    <NAME>_graph_indicator.txt   one int per vertex, 1-indexed graph id
    <NAME>_node_labels.txt       one int per vertex, the vertex label

Output:
    data/raw/<NAME>.unweighted.json - same schema as data/sample/illustrative.json
    but with edge weights set to 1.0 (placeholder; ``gen_weights.py`` will
    overwrite them in the weighted/ outputs).

The conversion is purely structural - no weights are invented here.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = PROJECT_ROOT / "data" / "raw"

DATASETS = [
    "MUTAG", "PTC_MR", "NCI1", "NCI109",
    "PROTEINS", "IMDB-BINARY", "COLLAB",
]


def read_ints(path: Path) -> list[int]:
    out: list[int] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if not s:
                continue
            out.append(int(s))
    return out


def degree_buckets(edges: list[tuple[int, int]], n_vertices: int,
                   num_buckets: int = 6) -> list[int]:
    """Synthetic vertex labels for unlabelled datasets.

    Vertex labels are derived from degree by bucketing into ``num_buckets``
    quantile bins. Six buckets is a defensible compromise: enough discrimination
    for the canonical-DFS-code framework to produce non-trivial pattern sets,
    not so many that every vertex ends up in its own label class.
    """
    deg = [0] * (n_vertices + 1)  # 1-indexed
    for u, v in edges:
        deg[u] += 1
        deg[v] += 1
    # Quantile thresholds from the actual degree distribution
    sorted_deg = sorted(deg[1:])
    cuts = [sorted_deg[max(0, int(len(sorted_deg) * q / num_buckets) - 1)]
            for q in range(1, num_buckets + 1)]
    labels = [0] * (n_vertices + 1)
    for i in range(1, n_vertices + 1):
        for b, cut in enumerate(cuts):
            if deg[i] <= cut:
                labels[i] = b
                break
        else:
            labels[i] = num_buckets - 1
    return labels[1:]  # 0-indexed result


def read_edges(path: Path) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if not s:
                continue
            a, _, b = s.partition(",")
            out.append((int(a.strip()), int(b.strip())))
    return out


def convert(name: str) -> Path:
    src = RAW_DIR / name
    a_file = src / f"{name}_A.txt"
    ind_file = src / f"{name}_graph_indicator.txt"
    lbl_file = src / f"{name}_node_labels.txt"

    for required in (a_file, ind_file):
        if not required.exists():
            raise FileNotFoundError(f"missing {required} - run download_tudataset.py first")

    graph_indicator = read_ints(ind_file)
    edges = read_edges(a_file)

    # Some TUDataset entries (IMDB-BINARY, COLLAB, REDDIT-*) ship without
    # vertex labels. Synthesise degree-bucket labels in that case so the
    # algorithm still has a label alphabet to work with.
    if lbl_file.exists():
        node_labels = read_ints(lbl_file)
    else:
        node_labels = degree_buckets(edges, len(graph_indicator))
        print(f"  [info] {name}: no node_labels.txt; using degree-bucket synthetic labels")

    assert len(graph_indicator) == len(node_labels), \
        f"{name}: graph_indicator and node_labels length mismatch"

    per_graph_vertices: dict[int, list[int]] = defaultdict(list)
    for vid_global, gid in enumerate(graph_indicator, start=1):
        per_graph_vertices[gid].append(vid_global)

    global_to_local: dict[int, tuple[int, int]] = {}
    for gid, vids in per_graph_vertices.items():
        for local, vid in enumerate(vids):
            global_to_local[vid] = (gid, local)

    per_graph_edges: dict[int, set[tuple[int, int]]] = defaultdict(set)
    for u, v in edges:
        gu, _ = global_to_local[u]
        gv, _ = global_to_local[v]
        assert gu == gv, f"{name}: edge ({u},{v}) crosses graphs {gu} != {gv}"
        _, lu = global_to_local[u]
        _, lv = global_to_local[v]
        if lu == lv:
            continue
        per_graph_edges[gu].add((min(lu, lv), max(lu, lv)))

    unique_labels = sorted({lbl for lbl in node_labels})
    label_to_index = {lbl: i for i, lbl in enumerate(unique_labels)}

    graphs_json = []
    for gid in sorted(per_graph_vertices.keys()):
        vids = per_graph_vertices[gid]
        verts = [
            {"id": local, "label": label_to_index[node_labels[vid - 1]]}
            for local, vid in enumerate(vids)
        ]
        edges_for_g = sorted(per_graph_edges.get(gid, set()))
        eds = [{"src": lo, "dst": hi, "weight": 1.0} for lo, hi in edges_for_g]
        graphs_json.append({"id": gid - 1, "vertices": verts, "edges": eds})

    out = {
        "name": name.lower(),
        "description": (
            f"{name} from TUDataset, structurally converted to the common JSON format used here. "
            "Edge weights are placeholders (1.0); use gen_weights.py to produce a "
            "weighted variant."
        ),
        "label_strings": [str(lbl) for lbl in unique_labels],
        "graphs": graphs_json,
    }

    # Normalise hyphens to underscores so the Java config can use plain
    # identifiers (e.g. imdb_binary instead of imdb-binary).
    safe = name.replace("-", "_")
    out_file = RAW_DIR / f"{safe}.unweighted.json"
    with out_file.open("w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2)

    print(f"  [done] {name}: {len(graphs_json)} graphs, "
          f"{len(unique_labels)} labels, {sum(len(g['edges']) for g in graphs_json)} edges "
          f"-> {out_file}")
    return out_file


def main(argv: Iterable[str]) -> int:
    argv = list(argv)
    datasets = DATASETS
    if argv and argv[0] == "--datasets" and len(argv) > 1:
        datasets = [d for d in argv[1].split(",") if d]
    for name in datasets:
        convert(name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
