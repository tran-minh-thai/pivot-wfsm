"""Synthesise edge weights for the converted TUDataset graphs in TWO
parallel schemas, written to two separate directories.

Schema (1) - ``per_instance``: each edge instance receives its own
independent sample, so two A-B edges in the same graph can carry different
weights. This is the general weighted-multigraph setting, and the one that
exposes the per-graph divergence between the MIN and AVG aggregators. Use it
for the pivot miner and for the embedding-store baseline (embed-min).

Schema (2) - ``static``: all edges that share the same
``(labelU, labelV, edgeLabel)`` triple receive THE SAME weight. This is
the static-edge-weight assumption made by WFSM-MaxPWS / DewgSpan / JCZ.
Under this schema W_min(P) and W_avg(P) collapse to functions of the
pattern's labels alone - σ_MIN and σ_AVG no longer separate. Use for
wfsm-maxpws, jcz-atw.

Input:  ``data/raw/<NAME>.unweighted.json``  (from to_common_format.py)
Output: ``data/weighted/per_instance/<dist>/<NAME>.<dist>.s<seed>.json``
        ``data/weighted/static/<dist>/<NAME>.<dist>.s<seed>.json``

Both schemas use the same seed so per-seed comparisons stay reproducible.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Iterable

import numpy as np
import yaml


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = PROJECT_ROOT / "data" / "raw"
WEIGHTED_DIR = PROJECT_ROOT / "data" / "weighted"
CONFIG = PROJECT_ROOT / "bench" / "config" / "weight_dists.yaml"

DATASETS = [
    "MUTAG", "PTC_MR", "NCI1", "NCI109",
    "PROTEINS", "IMDB-BINARY", "COLLAB",
]


def load_config() -> dict:
    with CONFIG.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def sample_weights(n: int, dist_name: str, params: dict, rng: np.random.Generator) -> np.ndarray:
    family = params["family"]
    if family == "gaussian":
        w = rng.normal(params["mean"], params["stddev"], size=n)
    elif family == "negative_exponential":
        w = rng.exponential(params["scale"], size=n)
    else:
        raise ValueError(f"unknown family for distribution '{dist_name}': {family}")
    lo, hi = params.get("clip", [0.0, 1.0])
    return np.clip(w, lo, hi)


def label_pairs(data: dict) -> list[tuple[int, int]]:
    """All distinct undirected vertex-label pairs present in the dataset."""
    seen: set[tuple[int, int]] = set()
    for g in data["graphs"]:
        vlabel = [v["label"] for v in g["vertices"]]
        for e in g["edges"]:
            lu, lv = vlabel[e["src"]], vlabel[e["dst"]]
            seen.add((min(lu, lv), max(lu, lv)))
    return sorted(seen)


def write_per_instance(data: dict, dataset: str, dist: str, seed: int,
                       params: dict) -> Path:
    """Each edge instance gets its own independent weight."""
    rng = np.random.default_rng(seed)
    total_edges = sum(len(g["edges"]) for g in data["graphs"])
    weights = sample_weights(total_edges, dist, params, rng)

    cursor = 0
    for g in data["graphs"]:
        for e in g["edges"]:
            e["weight"] = float(weights[cursor])
            cursor += 1

    data["name"] = f"{dataset.lower()}.{dist}.s{seed}.per_instance"
    data["description"] = (
        f"{dataset} with PER-INSTANCE edge weights drawn from {dist} (seed {seed}). "
        "Each edge has an independent weight - same label pair can have different "
        "weights in different graphs (or even in the same graph)."
    )
    out_dir = WEIGHTED_DIR / "per_instance" / dist
    out_dir.mkdir(parents=True, exist_ok=True)
    safe = dataset.replace("-", "_")
    out_file = out_dir / f"{safe}.{dist}.s{seed}.json"
    with out_file.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
    return out_file


def write_static(data: dict, dataset: str, dist: str, seed: int,
                 params: dict) -> Path:
    """All edges sharing (labelU, labelV) get the same weight."""
    # Important: re-load data structure since per_instance() mutates it in place.
    # Caller is responsible for passing a fresh copy.
    rng = np.random.default_rng(seed)
    pairs = label_pairs(data)
    pair_weights = sample_weights(len(pairs), dist, params, rng)
    pair_to_weight = {pair: float(w) for pair, w in zip(pairs, pair_weights)}

    for g in data["graphs"]:
        vlabel = [v["label"] for v in g["vertices"]]
        for e in g["edges"]:
            lu, lv = vlabel[e["src"]], vlabel[e["dst"]]
            key = (min(lu, lv), max(lu, lv))
            e["weight"] = pair_to_weight[key]

    data["name"] = f"{dataset.lower()}.{dist}.s{seed}.static"
    data["description"] = (
        f"{dataset} with STATIC edge weights drawn from {dist} (seed {seed}). "
        "Each label pair has one fixed weight reused across every matching edge "
        "in the database. This satisfies the WFSM-MaxPWS / JCZ static-weight "
        "assumption (Islam et al. 2024, §3)."
    )
    out_dir = WEIGHTED_DIR / "static" / dist
    out_dir.mkdir(parents=True, exist_ok=True)
    safe = dataset.replace("-", "_")
    out_file = out_dir / f"{safe}.{dist}.s{seed}.json"
    with out_file.open("w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
    return out_file


def generate_one(dataset: str, dist: str, params: dict, seed: int) -> list[Path]:
    safe = dataset.replace("-", "_")
    src = RAW_DIR / f"{safe}.unweighted.json"
    if not src.exists():
        raise FileNotFoundError(f"missing {src} - run to_common_format.py first")
    with src.open(encoding="utf-8") as fh:
        raw = json.load(fh)

    written: list[Path] = []
    # Per-instance: mutate a fresh copy
    written.append(write_per_instance(json.loads(json.dumps(raw)), dataset, dist, seed, params))
    # Static: another fresh copy
    written.append(write_static(json.loads(json.dumps(raw)), dataset, dist, seed, params))
    return written


def _parse_overrides(argv: list[str]) -> dict[str, list[str]]:
    """Minimal `--flag a,b` parser for --datasets/--seeds/--dists."""
    out: dict[str, list[str]] = {}
    i = 0
    while i < len(argv) - 1:
        if argv[i] in ("--datasets", "--seeds", "--dists"):
            out[argv[i].lstrip("-")] = [x for x in argv[i + 1].split(",") if x]
            i += 2
        else:
            i += 1
    return out


def main(argv: Iterable[str]) -> int:
    argv = list(argv)
    over = _parse_overrides(argv)

    cfg = load_config()
    seeds = [int(s) for s in over.get("seeds", cfg.get("seeds", [42]))]
    dists = cfg.get("distributions", {})
    if "dists" in over:  # keep only requested distributions
        dists = {k: v for k, v in dists.items() if k in over["dists"]}
    datasets = over.get("datasets", DATASETS)

    total = 0
    for name in datasets:
        for dist_name, dist_params in dists.items():
            for seed in seeds:
                paths = generate_one(name, dist_name, dist_params, int(seed))
                for p in paths:
                    print(f"  [done] {p}")
                    total += 1
    print(f"[gen_weights] wrote {total} files "
          f"({len(datasets)} datasets * {len(dists)} dists * {len(seeds)} seeds * 2 schemas)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
