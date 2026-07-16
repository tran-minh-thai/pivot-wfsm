"""Slice a weighted-multigraph JSON into fixed database sizes N for the
memory-scalability sweep (experiment E1/E4 in bench/pivot/EXPERIMENTS_SCALE.md).

The database-size axis N (number of transaction graphs) is isolated cleanly by
taking, from ONE weighted source file, deterministic subsets of increasing size
on the SAME graph distribution. Default is a prefix (graphs[:N]); pass --seed for
a reproducible random subset instead.

Usage:
    python data/scripts/subsample.py <source.json> <outdir> <N1,N2,...> [--seed S]

Writes <outdir>/<stem>.n<N>.json for each N <= total (larger N are clamped once
to the full size and reported). The source is read a single time.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2

    src = Path(argv[0])
    outdir = Path(argv[1])
    sizes = sorted({int(x) for x in argv[2].split(",") if x})
    seed = None
    if "--seed" in argv:
        seed = int(argv[argv.index("--seed") + 1])

    outdir.mkdir(parents=True, exist_ok=True)
    with src.open(encoding="utf-8") as fh:
        data = json.load(fh)

    graphs = data["graphs"]
    total = len(graphs)
    base_name = data.get("name", src.stem)
    label_strings = data.get("label_strings", [])
    stem = src.name[:-5] if src.name.endswith(".json") else src.name

    order = list(range(total))
    if seed is not None:
        import random
        random.Random(seed).shuffle(order)

    written_full = False
    for n in sizes:
        eff = min(n, total)
        if eff == total and written_full:
            print(f"  [skip] N={n}: exceeds total {total}, already wrote full")
            continue
        idx = order[:eff]
        subset = {
            "name": f"{base_name}.n{eff}",
            "label_strings": label_strings,
            "graphs": [graphs[i] for i in idx],
        }
        out = outdir / f"{stem}.n{eff}.json"
        with out.open("w", encoding="utf-8") as fh:
            json.dump(subset, fh)
        print(f"  [done] {out}  ({eff}/{total} graphs)")
        if eff == total:
            written_full = True
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
