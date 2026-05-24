"""Standalone eval on a split — AUC, accuracy, breakdown by dataset source."""
from __future__ import annotations

import argparse
import logging
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import roc_auc_score
from torch.utils.data import DataLoader
from torchvision import transforms

from .data.datasets import PramanaDataset, build_dataset
from .models.backbones import PramanaClassifier

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--dataset", default="combined")
    ap.add_argument("--split", default="val", choices=["train", "val"])
    ap.add_argument("--batch-size", type=int, default=64)
    args = ap.parse_args()

    state = torch.load(args.checkpoint, map_location="cpu")
    backbone = state.get("args", {}).get("backbone", "mobilenet_v3_small")
    model = PramanaClassifier(backbone=backbone, pretrained=False)
    model.load_state_dict(state["model"])
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device).eval()

    train_samples, val_samples = build_dataset(args.dataset)
    samples = train_samples if args.split == "train" else val_samples
    tf = transforms.Compose([transforms.Resize(256), transforms.CenterCrop(224), transforms.ToTensor()])
    ds = PramanaDataset(samples, transform=tf)
    loader = DataLoader(ds, batch_size=args.batch_size, shuffle=False, num_workers=4)

    ys, ps, srcs = [], [], []
    with torch.no_grad():
        for batch_idx, (x, y) in enumerate(loader):
            x = x.to(device)
            logits = model(x)
            p = torch.softmax(logits, dim=-1)[:, 1].cpu().numpy()
            ps.extend(p.tolist())
            ys.extend(y.tolist())
            # Map back to source via offset
            offset = batch_idx * args.batch_size
            for i in range(len(y)):
                srcs.append(samples[offset + i].source)

    ys, ps = np.asarray(ys), np.asarray(ps)
    overall_auc = roc_auc_score(ys, ps) if len(set(ys)) > 1 else float("nan")
    overall_acc = float(((ps > 0.5).astype(int) == ys).mean())
    log.info("Overall  AUC=%.4f  Acc=%.4f  (N=%d)", overall_auc, overall_acc, len(ys))

    # Per-source breakdown
    by_src = defaultdict(lambda: ([], []))
    for y, p, s in zip(ys, ps, srcs):
        by_src[s][0].append(int(y))
        by_src[s][1].append(float(p))
    for src, (yy, pp) in by_src.items():
        yy_np = np.asarray(yy)
        pp_np = np.asarray(pp)
        auc = roc_auc_score(yy_np, pp_np) if len(set(yy_np)) > 1 else float("nan")
        acc = float(((pp_np > 0.5).astype(int) == yy_np).mean())
        log.info("  [%-8s]  AUC=%.4f  Acc=%.4f  (N=%d)", src, auc, acc, len(yy))


if __name__ == "__main__":
    _cli()
