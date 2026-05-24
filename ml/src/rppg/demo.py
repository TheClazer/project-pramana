"""Run rPPG on a webcam recording or any face video; print the live SNR.

Usage:
    python -m src.rppg.demo --video path/to/face_30fps.mp4
    python -m src.rppg.demo --video path/to/screen_recording_of_deepfake.mp4

The bible §6 validation case: a real face video should yield SNR > 0.5;
a screen recording of a deepfake should yield SNR < 0.2 (the hard-veto
threshold).
"""
from __future__ import annotations

import argparse
import sys
from collections import deque
from pathlib import Path

import cv2
import numpy as np

from .pos import heart_band_snr, pos_project


def _roi_means(frame_rgb: np.ndarray) -> tuple[float, float, float]:
    """Aggregate RGB means over fixed-position ROIs (forehead, cheeks, nose, chin).
    The Kotlin port uses the exact same five normalized centers + 17x17 patch.
    """
    h, w, _ = frame_rgb.shape
    centers = [
        (0.5, 0.30), (0.3, 0.55), (0.7, 0.55), (0.5, 0.45), (0.5, 0.75)
    ]
    half = 8
    sums = np.zeros(3, dtype=np.float64)
    count = 0
    for nx, ny in centers:
        cx, cy = int(nx * w), int(ny * h)
        x0, x1 = max(0, cx - half), min(w, cx + half + 1)
        y0, y1 = max(0, cy - half), min(h, cy + half + 1)
        patch = frame_rgb[y0:y1, x0:x1]
        sums += patch.reshape(-1, 3).sum(axis=0)
        count += patch.shape[0] * patch.shape[1]
    if count == 0:
        return 0.0, 0.0, 0.0
    means = sums / count
    return float(means[0]), float(means[1]), float(means[2])


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video", type=Path, required=True)
    ap.add_argument("--window", type=int, default=30)
    ap.add_argument("--fps", type=float, default=30.0)
    args = ap.parse_args()

    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        print(f"cannot open {args.video}", file=sys.stderr); sys.exit(2)

    r_buf, g_buf, b_buf = deque(maxlen=args.window), deque(maxlen=args.window), deque(maxlen=args.window)
    n_frames = 0
    while True:
        ok, frame_bgr = cap.read()
        if not ok:
            break
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        r, g, b = _roi_means(frame_rgb)
        r_buf.append(r); g_buf.append(g); b_buf.append(b)
        n_frames += 1
        if len(r_buf) == args.window and n_frames % 5 == 0:
            proj = pos_project(np.asarray(r_buf), np.asarray(g_buf), np.asarray(b_buf))
            res = heart_band_snr(proj, fps=args.fps)
            print(f"frame {n_frames:5d}  SNR={res.snr:.3f}  peak={res.peak_hz:.2f} Hz  ({res.bpm:.1f} BPM)")
    cap.release()


if __name__ == "__main__":
    _cli()
