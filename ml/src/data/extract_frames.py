"""Bridge: FaceForensics++ / Celeb-DF VIDEOS -> face FRAMES the trainer expects.

The official downloaders give you .mp4 videos. `datasets.py` expects extracted
images laid out as:
    <out>/original/*.jpg      (real)
    <out>/manipulated/*.jpg   (fake)

This walks a folder of videos, classifies each as real/fake from its path, grabs
every Nth frame, center-crops to a square, resizes, and writes the JPGs. Point the
trainer at <out> with --dataset combined (place <out> at ml/datasets/faceforensics).

Usage:
    python -m ml.src.data.extract_frames <videos_dir> ml/datasets/faceforensics --every 30 --max-per-video 10
"""
from __future__ import annotations

import argparse
import logging
from pathlib import Path

import cv2

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

# Path keywords that mark a video as FAKE (FF++ manipulation methods + Celeb-DF synthesis).
FAKE_HINTS = ("manipulated", "altered", "synthesis", "fake", "deepfakes",
              "face2face", "faceswap", "neuraltextures", "deepfakedetection")
REAL_HINTS = ("original", "real", "actor", "youtube", "pristine")
VIDEO_EXTS = (".mp4", ".avi", ".mov", ".mkv")


def label_for(path: Path) -> str | None:
    p = str(path).lower()
    if any(h in p for h in FAKE_HINTS):
        return "manipulated"
    if any(h in p for h in REAL_HINTS):
        return "original"
    return None   # unknown -> skipped (caller warned)


def extract(videos_dir: Path, out_dir: Path, every: int, max_per_video: int, size: int) -> None:
    videos = [p for p in videos_dir.rglob("*") if p.suffix.lower() in VIDEO_EXTS]
    if not videos:
        log.error("No videos found under %s (looked for %s)", videos_dir, VIDEO_EXTS)
        return
    (out_dir / "original").mkdir(parents=True, exist_ok=True)
    (out_dir / "manipulated").mkdir(parents=True, exist_ok=True)
    log.info("Found %d videos", len(videos))

    skipped = 0
    for vi, vpath in enumerate(videos):
        label = label_for(vpath)
        if label is None:
            skipped += 1
            continue
        cap = cv2.VideoCapture(str(vpath))
        if not cap.isOpened():
            log.warning("could not open %s", vpath)
            continue
        saved, idx = 0, 0
        stem = vpath.stem
        while saved < max_per_video:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % every == 0:
                h, w = frame.shape[:2]
                s = min(h, w)
                y0, x0 = (h - s) // 2, (w - s) // 2
                crop = frame[y0:y0 + s, x0:x0 + s]
                crop = cv2.resize(crop, (size, size), interpolation=cv2.INTER_AREA)
                out = out_dir / label / f"{label}_{vi:05d}_{stem}_{saved:03d}.jpg"
                cv2.imwrite(str(out), crop, [cv2.IMWRITE_JPEG_QUALITY, 95])
                saved += 1
            idx += 1
        cap.release()
        if vi % 25 == 0:
            log.info("…%d/%d videos", vi, len(videos))

    real = len(list((out_dir / "original").glob("*.jpg")))
    fake = len(list((out_dir / "manipulated").glob("*.jpg")))
    log.info("Done. real=%d fake=%d (skipped %d videos with unknown label)", real, fake, skipped)
    if skipped:
        log.warning("Skipped videos had no real/fake hint in their path — check the layout.")


def _cli() -> None:
    ap = argparse.ArgumentParser(description="Extract face frames from deepfake video datasets")
    ap.add_argument("videos_dir", type=Path, help="folder of downloaded videos (searched recursively)")
    ap.add_argument("out_dir", type=Path, help="output, e.g. ml/datasets/faceforensics")
    ap.add_argument("--every", type=int, default=30, help="sample every Nth frame (30 ≈ 1/sec)")
    ap.add_argument("--max-per-video", type=int, default=10, help="cap frames per video")
    ap.add_argument("--size", type=int, default=256, help="output square size (px)")
    args = ap.parse_args()
    extract(args.videos_dir, args.out_dir, args.every, args.max_per_video, args.size)


if __name__ == "__main__":
    _cli()
