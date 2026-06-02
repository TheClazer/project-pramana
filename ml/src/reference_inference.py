"""Reference inference path — the CONTRACT.

Bible §6, §13 MANUAL: "Frame-to-tensor preprocessing — Pixel layout,
normalization range, channel order. Tiny mistakes here are silent and cause
the model to underperform. Implement to match the Python reference exactly."

This file defines THE preprocessing the Android side must reproduce in
`detection/PreprocessIntoTensor.kt`. If Kotlin produces different floats
for the same image, Kotlin is wrong.

Contract:
    Input:  PIL.Image RGB, any size
    Step 1: center-square crop to min(w, h)
    Step 2: bilinear resize to 224x224
    Step 3: pack as NCHW float32 in [0, 1] (no mean/std subtraction)
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Tuple

import numpy as np
from PIL import Image

# NOTE: torch + timm (via models.backbones) are imported LAZILY inside the
# functions that need them. `preprocess()` and `softmax_fake_score()` — the
# cross-language preprocessing contract the Android side mirrors — depend only
# on numpy + PIL, so the contract is checkable without the training stack.

TARGET_SIZE = 224


def preprocess(image: Image.Image) -> np.ndarray:
    """Image -> NCHW float32 [1, 3, 224, 224] in [0, 1]."""
    img = image.convert("RGB")
    w, h = img.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    img = img.crop((left, top, left + side, top + side))
    img = img.resize((TARGET_SIZE, TARGET_SIZE), Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32) / 255.0          # HWC, [0,1]
    arr = np.transpose(arr, (2, 0, 1))                       # CHW
    return np.expand_dims(arr, axis=0)                       # NCHW


def softmax_fake_score(logits: np.ndarray) -> float:
    """[1, 2] logits -> P(fake) scalar."""
    e = np.exp(logits - logits.max(axis=-1, keepdims=True))
    p = e / e.sum(axis=-1, keepdims=True)
    return float(p[0, 1])


def run_pytorch(model, image: Image.Image) -> Tuple[float, np.ndarray]:
    """Returns (fake_score, raw_logits). `model` is a PramanaClassifier (torch)."""
    import torch  # lazy — keeps the preprocessing contract torch-free
    x = torch.from_numpy(preprocess(image))
    model.eval()
    with torch.no_grad():
        logits = model(x).cpu().numpy()
    return softmax_fake_score(logits), logits


# ----------------------------- CLI ----------------------------- #
def _cli() -> None:
    ap = argparse.ArgumentParser(description="Reference inference (the Android preprocessing contract)")
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--checkpoint", type=Path, default=None,
                    help="optional .pt checkpoint; if absent uses random weights to test plumbing")
    ap.add_argument("--backbone", default="mobilenet_v3_small")
    ap.add_argument("--out-json", type=Path, default=None,
                    help="write the preprocessed tensor + logits as JSON for Android comparison")
    args = ap.parse_args()

    import torch  # lazy
    from .models.backbones import PramanaClassifier  # lazy (pulls in timm)
    model = PramanaClassifier(backbone=args.backbone, pretrained=False)
    if args.checkpoint is not None:
        state = torch.load(args.checkpoint, map_location="cpu")
        model.load_state_dict(state.get("model", state))
    image = Image.open(args.image)
    score, logits = run_pytorch(model, image)
    pre = preprocess(image)
    print(f"image: {args.image}")
    print(f"preprocessed shape: {pre.shape}  dtype: {pre.dtype}")
    print(f"preprocessed [0,0,0,0]: {float(pre[0,0,0,0]):.6f}")
    print(f"logits: {logits.flatten().tolist()}")
    print(f"P(fake) = {score:.6f}")
    if args.out_json is not None:
        args.out_json.write_text(json.dumps({
            "preprocessed_first_pixel": [float(pre[0, c, 0, 0]) for c in range(3)],
            "preprocessed_center_pixel": [float(pre[0, c, 112, 112]) for c in range(3)],
            "logits": logits.flatten().tolist(),
            "p_fake": score
        }, indent=2))


if __name__ == "__main__":
    _cli()
