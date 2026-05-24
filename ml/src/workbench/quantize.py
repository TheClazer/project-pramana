"""Submit a quantization job to AI Hub Workbench.

Bible §6: "Submit a quantize job — once with INT8, once with INT4. Receive
two compiled .tflite files (with HTP delegate metadata baked in)."

Calibration: we use 100 images from the stand-in dataset by default; for the
real model use a held-out FF++ subset of ~500 images.
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from ..data.datasets import build_dataset
from ..reference_inference import preprocess

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--compile-job-id", required=True, help="id printed by compile.py")
    ap.add_argument("--precision", choices=["int8", "int4"], required=True)
    ap.add_argument("--target-device", default="Snapdragon 8 Gen 2")
    ap.add_argument("--calib-dataset", default="stand_in")
    ap.add_argument("--calib-samples", type=int, default=100)
    ap.add_argument("--out-dir", type=Path, default=Path("runs/workbench"))
    args = ap.parse_args()

    try:
        import qai_hub as hub
    except ImportError:
        log.error("qai-hub not installed. Run: pip install qai-hub")
        sys.exit(2)

    args.out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Build calibration dataset — N images → numpy stack
    train, _val = build_dataset(args.calib_dataset)
    samples = train[: args.calib_samples]
    log.info("Calibration set: %d images from %s", len(samples), args.calib_dataset)
    arrs = []
    for s in samples:
        img = Image.open(s.path)
        arrs.append(preprocess(img).astype(np.float32))
    calib = np.concatenate(arrs, axis=0)  # [N, 3, 224, 224]

    calibration_data = {"image": calib}

    # 2. Resolve the compiled model from the previous compile job
    compile_job = hub.get_job(args.compile_job_id)
    compiled = compile_job.get_target_model()

    # 3. Submit quantize job
    device = hub.Device(args.target_device)
    weight_dtype = "int4" if args.precision == "int4" else "int8"
    log.info("Submitting %s quantize job …", args.precision)
    qjob = hub.submit_quantize_job(
        model=compiled,
        device=device,
        calibration_data=calibration_data,
        weights_dtype=weight_dtype,
        activations_dtype="int8",   # activations stay int8 even with int4 weights
    )
    log.info("Quantize job id: %s", qjob.job_id)
    log.info("Dashboard:       %s", qjob.url)
    qjob.wait()

    quantized = qjob.get_target_model()
    out_tflite = args.out_dir / f"pramana-{args.precision}.tflite"
    quantized.download(str(out_tflite))
    log.info("Quantized .tflite: %s", out_tflite)
    log.info("Drop into android/app/src/main/assets/%s", out_tflite.name)
    print(qjob.job_id)


if __name__ == "__main__":
    _cli()
