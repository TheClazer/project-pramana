"""Dataset loaders — FF++, Celeb-DF, IIIT-CFW augmentation, plus a small
public stand-in so the training pipeline runs before your dataset approvals
arrive.

All loaders expose the same shape:
    (PIL.Image RGB, int label)            label: 0 = real, 1 = fake

Bible §6 datasets:
    FaceForensics++ (c23 split)           primary
    Celeb-DF v2                            hard-fakes augmentation
    IIIT-CFW (real faces only)             Indian-face augmentation
"""
from __future__ import annotations

import csv
import logging
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator

import numpy as np
from PIL import Image
from torch.utils.data import Dataset

log = logging.getLogger(__name__)

# Root for downloaded datasets. .gitignored.
DEFAULT_ROOT = Path(__file__).resolve().parents[2] / "datasets"


# ----------------------------- Sample shape ----------------------------- #
@dataclass(frozen=True)
class Sample:
    path: Path
    label: int                  # 0 real, 1 fake
    source: str                 # "ffpp" | "celebdf" | "iiitcfw" | "standin"


# ----------------------------- Manifest ----------------------------- #
def write_split_manifest(samples: list[Sample], out_path: Path) -> None:
    """Write a CSV with (path, label, source) — what the loader reads back."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["path", "label", "source"])
        for s in samples:
            w.writerow([str(s.path), s.label, s.source])
    log.info("Wrote manifest: %s (%d samples)", out_path, len(samples))


def read_split_manifest(path: Path) -> list[Sample]:
    with path.open("r", encoding="utf-8") as f:
        r = csv.DictReader(f)
        return [Sample(Path(row["path"]), int(row["label"]), row["source"]) for row in r]


# ----------------------------- Discoverers ----------------------------- #
def discover_faceforensics(root: Path) -> list[Sample]:
    """FF++ layout: c23/ with original/ and manipulated/. We only need leaf images.
    The official FF++ extractor pulls frames at ~30 fps; we expect already-extracted
    .jpg/.png frames here.
    """
    samples: list[Sample] = []
    if not (root / "original").exists() or not (root / "manipulated").exists():
        log.warning("FF++ root %s missing original/ or manipulated/ — skip", root)
        return samples
    for p in (root / "original").rglob("*.jpg"):
        samples.append(Sample(p, 0, "ffpp"))
    for p in (root / "manipulated").rglob("*.jpg"):
        samples.append(Sample(p, 1, "ffpp"))
    return samples


def discover_celebdf(root: Path) -> list[Sample]:
    """Celeb-DF v2 has Celeb-real/ and Celeb-synthesis/ at top level."""
    samples: list[Sample] = []
    real = root / "Celeb-real"
    fake = root / "Celeb-synthesis"
    if not real.exists() or not fake.exists():
        log.warning("Celeb-DF root %s missing Celeb-real/Celeb-synthesis — skip", root)
        return samples
    for p in real.rglob("*.jpg"):
        samples.append(Sample(p, 0, "celebdf"))
    for p in fake.rglob("*.jpg"):
        samples.append(Sample(p, 1, "celebdf"))
    return samples


def discover_iiitcfw(root: Path) -> list[Sample]:
    """IIIT-CFW has real Indian face crops — all label=0 (augmentation for bias)."""
    samples: list[Sample] = []
    if not root.exists():
        log.warning("IIIT-CFW root %s missing — skip", root)
        return samples
    for p in root.rglob("*.jpg"):
        samples.append(Sample(p, 0, "iiitcfw"))
    return samples


def discover_frames(root: Path) -> list[Sample]:
    """Generic extracted-frames layout produced by extract_frames.py:
        <root>/original/*.jpg     (real, label 0)
        <root>/manipulated/*.jpg  (fake, label 1)
    This is where Celeb-DF / FF++ frames land after extraction.
    """
    samples: list[Sample] = []
    if not (root / "original").exists() or not (root / "manipulated").exists():
        log.warning("frames root %s missing original/ or manipulated/ — skip", root)
        return samples
    for p in (root / "original").rglob("*.jpg"):
        samples.append(Sample(p, 0, "frames"))
    for p in (root / "manipulated").rglob("*.jpg"):
        samples.append(Sample(p, 1, "frames"))
    return samples


def discover_standin(_root: Path | None = None) -> list[Sample]:
    """Use the stand-in synthesis when no real datasets are present."""
    from .sample_stand_in import build_or_load_standin
    return build_or_load_standin()


# ----------------------------- Top-level builder ----------------------------- #
def build_dataset(
    dataset: str,
    root: Path = DEFAULT_ROOT,
    seed: int = 0,
    balance: bool = False,
) -> tuple[list[Sample], list[Sample]]:
    """Returns (train, val) sample lists. 80/20 split, seeded.

    If `balance` is True, the majority class is randomly downsampled to match the
    minority class count — important for Celeb-DF/FF++ which are heavily fake-skewed
    (a model trained on imbalanced data just learns to always say 'fake')."""
    if dataset == "stand_in":
        all_samples = discover_standin()
    elif dataset == "combined":
        all_samples = (
            discover_frames(root / "frames")
            + discover_faceforensics(root / "faceforensics")
            + discover_celebdf(root / "celebdf")
            + discover_iiitcfw(root / "iiitcfw")
        )
        if not all_samples:
            log.warning("No real datasets found — falling back to stand-in")
            all_samples = discover_standin()
    elif dataset == "frames":
        all_samples = discover_frames(root / "frames")
    elif dataset == "ffpp":
        all_samples = discover_faceforensics(root / "faceforensics")
    elif dataset == "celebdf":
        all_samples = discover_celebdf(root / "celebdf")
    else:
        raise ValueError(f"unknown dataset key: {dataset}")

    if not all_samples:
        raise RuntimeError(
            "No samples discovered. Either no images are at the expected paths, "
            "or your dataset approvals are still pending — try `--dataset stand_in`."
        )

    rng = random.Random(seed)

    if balance:
        reals = [s for s in all_samples if s.label == 0]
        fakes = [s for s in all_samples if s.label == 1]
        n = min(len(reals), len(fakes))
        if n == 0:
            raise RuntimeError(f"Cannot balance: reals={len(reals)} fakes={len(fakes)}")
        rng.shuffle(reals); rng.shuffle(fakes)
        all_samples = reals[:n] + fakes[:n]
        log.info("Balanced to %d real + %d fake = %d (from %d)",
                 n, n, 2 * n, len(reals) + len(fakes))

    rng.shuffle(all_samples)
    pivot = int(len(all_samples) * 0.8)
    train, val = all_samples[:pivot], all_samples[pivot:]
    log.info("Split %d -> train=%d val=%d (%s)", len(all_samples), len(train), len(val), dataset)
    return train, val


# ----------------------------- Torch Dataset wrapper ----------------------------- #
class PramanaDataset(Dataset):
    """PIL → tensor transform delegated to caller — keeps preprocessing
    centralized in `reference_inference.py` so Sealer/Verifier match."""

    def __init__(self, samples: list[Sample], transform: Callable):
        self.samples = samples
        self.transform = transform

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int):
        s = self.samples[idx]
        img = Image.open(s.path).convert("RGB")
        x = self.transform(img)
        return x, s.label

    def iter_samples(self) -> Iterator[Sample]:
        return iter(self.samples)
