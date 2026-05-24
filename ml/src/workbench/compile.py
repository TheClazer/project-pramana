"""Compile a trained PyTorch checkpoint to a deployable artifact via Qualcomm
AI Hub Workbench.

Prereqs:
    1. qai-hub configure --api_token <YOUR_TOKEN>          (one-time)
    2. Have a checkpoint from ml/src/train.py

The script:
    - Loads the PyTorch checkpoint
    - Traces it with a (1, 3, 224, 224) example input
    - Submits a `submit_compile_job` to AI Hub
    - Returns the compile job id (use it as input to quantize.py / profile.py)

Bible §6 — "Submit a compile job. Workbench compiles the model into a
deployable artifact."
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import torch

from ..models.backbones import PramanaClassifier

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", type=Path, required=True)
    ap.add_argument("--backbone", default="mobilenet_v3_small")
    ap.add_argument("--target-device",
                    default="Snapdragon 8 Gen 2",
                    help="Cloud device family to compile for. List options with "
                         "`python -c 'import qai_hub; print([d.name for d in qai_hub.get_devices()])'`.")
    ap.add_argument("--out-dir", type=Path, default=Path("runs/workbench"))
    args = ap.parse_args()

    try:
        import qai_hub as hub
    except ImportError:
        log.error("qai-hub not installed. Run: pip install qai-hub qai-hub-models")
        sys.exit(2)

    args.out_dir.mkdir(parents=True, exist_ok=True)

    # 1. Build model + load weights
    model = PramanaClassifier(backbone=args.backbone, pretrained=False)
    state = torch.load(args.checkpoint, map_location="cpu")
    model.load_state_dict(state.get("model", state))
    model.eval()

    # 2. Trace
    example = torch.randn(1, 3, 224, 224)
    traced = torch.jit.trace(model, example)
    traced_path = args.out_dir / "traced.pt"
    traced.save(str(traced_path))
    log.info("Traced model: %s", traced_path)

    # 3. Submit compile job
    device = hub.Device(args.target_device)
    log.info("Submitting compile job for device %s …", args.target_device)
    compile_job = hub.submit_compile_job(
        model=traced_path,
        device=device,
        input_specs={"image": (1, 3, 224, 224)},
        options="--target_runtime tflite",
    )
    log.info("Compile job id: %s", compile_job.job_id)
    log.info("Dashboard:      %s", compile_job.url)

    # 4. Wait + download
    log.info("Waiting for compile to finish …")
    compile_job.wait()
    compiled = compile_job.get_target_model()
    out_tflite = args.out_dir / f"{args.backbone}_fp32.tflite"
    compiled.download(str(out_tflite))
    log.info("Compiled artifact: %s", out_tflite)
    print(compile_job.job_id)


if __name__ == "__main__":
    _cli()
