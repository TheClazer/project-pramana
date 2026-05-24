"""Grad-CAM heatmap generator — Stretch 1 (bible §15).

Generates a 7×7 or 14×14 attention map from the last convolutional layer.
Engineer A delivers the Python reference; the heatmap data is exported into
the manifest's `detection` block on tap (not every frame — too expensive per
bible §6).
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from .models.backbones import PramanaClassifier
from .reference_inference import preprocess


def grad_cam(model: PramanaClassifier, image: Image.Image, target_class: int = 1) -> np.ndarray:
    """Returns a [H, W] heatmap normalized to [0, 1].

    For MobileNet-V3, the last conv block outputs 7×7 spatial maps when the
    input is 224×224. We hook the forward+backward to grab the activation
    and gradient, then average over channels weighted by the gradient.
    """
    model.eval()
    x = torch.from_numpy(preprocess(image)).requires_grad_(False)

    # Find the last conv module. timm's mobilenetv3_small_100 exposes blocks[-1].
    last_block = None
    for m in model.body.modules():
        if isinstance(m, torch.nn.Conv2d):
            last_block = m
    if last_block is None:
        raise RuntimeError("Could not locate a Conv2d to hook")

    feats: list[torch.Tensor] = []
    grads: list[torch.Tensor] = []

    def fwd_hook(_module, _input, output):
        feats.append(output)

    def bwd_hook(_module, _grad_input, grad_output):
        grads.append(grad_output[0])

    h1 = last_block.register_forward_hook(fwd_hook)
    h2 = last_block.register_full_backward_hook(bwd_hook)
    try:
        logits = model(x)
        score = logits[0, target_class]
        model.zero_grad()
        score.backward()
    finally:
        h1.remove(); h2.remove()

    a = feats[0][0]                    # [C, H, W]
    g = grads[0][0]                    # [C, H, W]
    weights = g.mean(dim=(1, 2))       # [C]
    cam = (weights[:, None, None] * a).sum(dim=0)
    cam = F.relu(cam)
    cam -= cam.min()
    if cam.max() > 0:
        cam /= cam.max()
    return cam.detach().cpu().numpy()


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--image", type=Path, required=True)
    ap.add_argument("--out-npy", type=Path, default=Path("gradcam.npy"))
    ap.add_argument("--backbone", default="mobilenet_v3_small")
    args = ap.parse_args()

    state = torch.load(args.checkpoint, map_location="cpu")
    model = PramanaClassifier(backbone=args.backbone, pretrained=False)
    model.load_state_dict(state["model"])

    img = Image.open(args.image)
    heat = grad_cam(model, img, target_class=1)
    np.save(args.out_npy, heat.astype(np.float32))
    print(f"Saved Grad-CAM heatmap shape={heat.shape} to {args.out_npy}")


if __name__ == "__main__":
    _cli()
