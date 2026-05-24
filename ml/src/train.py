"""Training loop. Targets:
    AUC > 0.85 on FF++ val
    AUC > 0.75 on Celeb-DF val
(Bible §6).

Saves best-by-AUC checkpoint plus the final epoch checkpoint.
"""
from __future__ import annotations

import argparse
import logging
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import roc_auc_score
from torch.utils.data import DataLoader
from torchvision import transforms
from tqdm import tqdm

from .data.datasets import PramanaDataset, build_dataset
from .models.backbones import PramanaClassifier

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def build_transform(augment: bool):
    base = [
        transforms.Resize(256),
        transforms.CenterCrop(224),
    ]
    if augment:
        base = [
            transforms.Resize(256),
            transforms.RandomCrop(224),
            transforms.RandomHorizontalFlip(),
            transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.2),
        ]
    base.append(transforms.ToTensor())   # converts [0,1] CHW float32 — matches reference_inference
    return transforms.Compose(base)


def evaluate(model: PramanaClassifier, loader: DataLoader, device: str) -> tuple[float, float]:
    model.eval()
    ys, ps = [], []
    with torch.no_grad():
        for x, y in loader:
            x = x.to(device)
            logits = model(x)
            p = torch.softmax(logits, dim=-1)[:, 1].cpu().numpy()
            ps.extend(p.tolist())
            ys.extend(y.tolist())
    ys = np.asarray(ys)
    ps = np.asarray(ps)
    auc = roc_auc_score(ys, ps) if len(set(ys)) > 1 else float("nan")
    acc = float(((ps > 0.5).astype(int) == ys).mean())
    return auc, acc


def train(args):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    log.info("Device: %s", device)

    train_samples, val_samples = build_dataset(args.dataset, seed=args.seed)
    if args.quick:
        train_samples = train_samples[:64]
        val_samples = val_samples[:32]

    train_ds = PramanaDataset(train_samples, transform=build_transform(augment=True))
    val_ds = PramanaDataset(val_samples, transform=build_transform(augment=False))

    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True,
                              num_workers=args.num_workers, drop_last=True, pin_memory=True)
    val_loader = DataLoader(val_ds, batch_size=args.batch_size, shuffle=False,
                            num_workers=args.num_workers, pin_memory=True)

    model = PramanaClassifier(backbone=args.backbone, pretrained=True).to(device)
    optim = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(optim, T_max=max(args.epochs, 1))
    loss_fn = torch.nn.CrossEntropyLoss()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    best_auc = -1.0
    for epoch in range(args.epochs):
        model.train()
        epoch_loss = 0.0
        for x, y in tqdm(train_loader, desc=f"epoch {epoch+1}/{args.epochs}"):
            x, y = x.to(device), y.to(device)
            optim.zero_grad()
            logits = model(x)
            loss = loss_fn(logits, y)
            loss.backward()
            optim.step()
            epoch_loss += loss.item() * x.size(0)
        sched.step()
        avg_loss = epoch_loss / len(train_ds)
        auc, acc = evaluate(model, val_loader, device)
        log.info("epoch %d  loss=%.4f  val_auc=%.4f  val_acc=%.4f", epoch + 1, avg_loss, auc, acc)
        if auc > best_auc:
            best_auc = auc
            torch.save({"model": model.state_dict(), "epoch": epoch + 1, "auc": auc, "args": vars(args)},
                       out_dir / "best.pt")
            log.info("  saved best @ AUC=%.4f", auc)

    torch.save({"model": model.state_dict(), "epoch": args.epochs, "args": vars(args)}, out_dir / "final.pt")
    log.info("Best val AUC: %.4f (saved to %s/best.pt)", best_auc, out_dir)


def _cli():
    ap = argparse.ArgumentParser(description="Train Pramāṇa deepfake classifier")
    ap.add_argument("--dataset", default="combined", choices=["stand_in", "ffpp", "celebdf", "combined"])
    ap.add_argument("--backbone", default="mobilenet_v3_small", choices=["mobilenet_v3_small", "efficientnet_b0"])
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--num-workers", type=int, default=4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--quick", action="store_true", help="tiny subset, single-epoch smoke test")
    ap.add_argument("--out-dir", default="runs/default")
    args = ap.parse_args()
    train(args)


if __name__ == "__main__":
    _cli()
