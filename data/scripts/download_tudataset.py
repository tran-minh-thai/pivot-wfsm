"""Download the seven TUDataset archives used by the evaluation:
MUTAG, PTC_MR, NCI1, NCI109 (chemistry); PROTEINS (biology);
IMDB-BINARY and COLLAB (social / collaboration networks).

Source: https://chrsmrrs.github.io/datasets/

Each archive expands into a folder containing:
    <NAME>_A.txt              edge list, one undirected edge per line: "src,dst"
    <NAME>_graph_indicator.txt one int per vertex: which graph it belongs to
    <NAME>_node_labels.txt    one int per vertex: the vertex label
    <NAME>_graph_labels.txt   one int per graph: the class label (ignored here)

Idempotent: each dataset has a SHA-256 manifest in ``data/raw/checksums.txt``
(written on first successful download); subsequent runs verify the manifest
and skip the network.
"""

from __future__ import annotations

import hashlib
import io
import sys
import zipfile
from pathlib import Path
from typing import Iterable
from urllib.error import URLError
from urllib.request import urlopen


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = PROJECT_ROOT / "data" / "raw"
CHECKSUM_FILE = RAW_DIR / "checksums.txt"

BASE_URL = "https://www.chrsmrrs.com/graphkerneldatasets"

DATASETS = [
    "MUTAG", "PTC_MR", "NCI1", "NCI109",
    "PROTEINS", "IMDB-BINARY", "COLLAB",
]

# NOTE: "_edge_labels.txt" was previously missing from this tuple, which is why
# no dataset in data/raw/ ever had edge labels and why every database was a
# strictly SIMPLE graph (zero parallel edges). Bond order lives in the edge
# label, and bond order is what make_multigraph.py expands into parallel edges.
# Not every dataset ships one (NCI1/NCI109/PROTEINS/IMDB-BINARY do not); the
# extractor below simply skips it when absent.
WANTED_SUFFIXES = ("_A.txt", "_graph_indicator.txt", "_node_labels.txt",
                   "_graph_labels.txt", "_edge_labels.txt")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_checksums() -> dict[str, str]:
    if not CHECKSUM_FILE.exists():
        return {}
    out: dict[str, str] = {}
    for line in CHECKSUM_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        h, _, name = line.partition("  ")
        if h and name:
            out[name.strip()] = h.strip()
    return out


def save_checksums(checksums: dict[str, str]) -> None:
    CHECKSUM_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# SHA-256 of the archive bytes we downloaded.",
             "# Lines are 'hash  dataset.zip'."]
    for name in sorted(checksums):
        lines.append(f"{checksums[name]}  {name}")
    CHECKSUM_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def archive_already_present(name: str, expected_hash: str) -> bool:
    target_dir = RAW_DIR / name
    if not target_dir.is_dir():
        return False
    have = [p.name for p in target_dir.iterdir() if p.is_file()]
    return any(h.endswith(WANTED_SUFFIXES) for h in have) and bool(expected_hash)


def download_one(name: str, existing_checksums: dict[str, str]) -> str:
    url = f"{BASE_URL}/{name}.zip"
    expected = existing_checksums.get(f"{name}.zip", "")

    if archive_already_present(name, expected):
        print(f"  [skip] {name}: already extracted ({expected[:12]}...)")
        return expected

    print(f"  [fetch] {url}")
    try:
        with urlopen(url, timeout=60) as resp:
            blob = resp.read()
    except URLError as e:
        raise SystemExit(f"download failed for {name}: {e}") from e

    actual = sha256(blob)
    if expected and actual != expected:
        raise SystemExit(
            f"{name}: hash mismatch - expected {expected}, got {actual}. "
            "Delete data/raw/checksums.txt to accept a new archive."
        )

    extract_archive(name, blob)
    print(f"  [done] {name}: sha256={actual[:12]}...")
    return actual


def extract_archive(name: str, blob: bytes) -> None:
    target_dir = RAW_DIR / name
    target_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(blob)) as zf:
        for member in zf.namelist():
            base = Path(member).name
            if not base.endswith(WANTED_SUFFIXES):
                continue
            with zf.open(member) as src, (target_dir / base).open("wb") as dst:
                dst.write(src.read())


def main(argv: Iterable[str]) -> int:
    argv = list(argv)
    datasets = DATASETS
    if argv and argv[0] == "--datasets" and len(argv) > 1:
        datasets = [d for d in argv[1].split(",") if d]

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    existing = load_checksums()

    new = dict(existing)
    for name in datasets:
        new[f"{name}.zip"] = download_one(name, existing)

    save_checksums(new)
    print(f"All {len(datasets)} datasets prepared under {RAW_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
