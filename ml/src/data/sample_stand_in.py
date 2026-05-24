"""A small synthetic stand-in so the training pipeline runs end-to-end before
FaceForensics++ and Celeb-DF approvals come through.

Generates 200 "real" and 200 "fake" 224x224 RGB images:
    real: smooth Perlin-style noise + face-like blob (Gaussian)
    fake: same shape but with added high-freq checkerboard artifacts
          (mimics common GAN/face-swap upsampling fingerprints)

The classifier should learn the high-freq cue easily — accuracy of >95% on
the stand-in is expected after one epoch. The point is plumbing, not realism.
Swap to the combined real dataset before you train for the demo.
"""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
from PIL import Image

from .datasets import Sample

log = logging.getLogger(__name__)

STANDIN_ROOT = Path(__file__).resolve().parents[2] / "datasets" / "_standin"


def _gaussian_blob(h: int, w: int, cx: float, cy: float, sigma: float) -> np.ndarray:
    y, x = np.ogrid[:h, :w]
    return np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * sigma**2))


def _smooth_noise(h: int, w: int, rng: np.random.Generator) -> np.ndarray:
    # Low-freq noise via downsample+upsample
    low = rng.random((h // 16, w // 16)).astype(np.float32)
    img = np.array(Image.fromarray((low * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR), dtype=np.float32)
    return img / 255.0


def _checkerboard(h: int, w: int, size: int) -> np.ndarray:
    y, x = np.indices((h, w))
    c = ((x // size) + (y // size)) % 2
    return c.astype(np.float32)


def _generate_one(label: int, seed: int) -> Image.Image:
    h, w = 224, 224
    rng = np.random.default_rng(seed)
    # Skin-tone base color
    skin = rng.uniform(0.65, 0.85, size=3).astype(np.float32)
    blob = _gaussian_blob(h, w, w / 2, h / 2, sigma=60)[..., None]
    noise = _smooth_noise(h, w, rng)[..., None]
    img = (skin * blob + 0.1 * noise) * 255.0

    if label == 1:
        # Fake: inject high-freq checkerboard artifact (upsampling fingerprint)
        artifact = _checkerboard(h, w, 2)[..., None] * 12.0
        img = img + artifact * blob   # only where the "face" is

    img = np.clip(img, 0, 255).astype(np.uint8)
    return Image.fromarray(img, mode="RGB")


def build_or_load_standin(n_per_class: int = 200) -> list[Sample]:
    """Materializes the stand-in dataset on first call; reuses on subsequent calls."""
    STANDIN_ROOT.mkdir(parents=True, exist_ok=True)
    real_dir = STANDIN_ROOT / "real"
    fake_dir = STANDIN_ROOT / "fake"
    real_dir.mkdir(exist_ok=True)
    fake_dir.mkdir(exist_ok=True)

    samples: list[Sample] = []
    needs_build = (
        len(list(real_dir.glob("*.png"))) < n_per_class
        or len(list(fake_dir.glob("*.png"))) < n_per_class
    )
    if needs_build:
        log.info("Generating stand-in dataset under %s (one-time)", STANDIN_ROOT)
        for i in range(n_per_class):
            _generate_one(0, seed=10_000 + i).save(real_dir / f"real_{i:04d}.png")
            _generate_one(1, seed=20_000 + i).save(fake_dir / f"fake_{i:04d}.png")
    for p in sorted(real_dir.glob("*.png")):
        samples.append(Sample(p, 0, "standin"))
    for p in sorted(fake_dir.glob("*.png")):
        samples.append(Sample(p, 1, "standin"))
    log.info("Stand-in dataset ready: %d samples (%d real + %d fake)", len(samples), n_per_class, n_per_class)
    return samples
