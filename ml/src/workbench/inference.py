"""Cloud inference sanity check — compare quantized output vs local PyTorch.

Bible §6: "Submit an inference job to verify outputs match the local PyTorch
output to within quantization tolerance. If they diverge by more than a few
percent, the quantization broke and you need to revisit calibration."
"""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image

from ..models.backbones import PramanaClassifier
from ..reference_inference import preprocess, softmax_fake_score

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quantize-job-id", required=True)
    ap.add_argument("--reference-image", type=Path, required=True)
    ap.add_argument("--checkpoint", type=Path, default=None,
                    help="local PyTorch checkpoint to compare against (optional but recommended)")
    ap.add_argument("--backbone", default="mobilenet_v3_small")
    ap.add_argument("--target-device", default="Snapdragon 8 Gen 2")
    args = ap.parse_args()

    try:
        import qai_hub as hub
    except ImportError:
        log.error("qai-hub not installed."); sys.exit(2)

    # 1. Cloud inference
    quantize_job = hub.get_job(args.quantize_job_id)
    quantized = quantize_job.get_target_model()
    image = Image.open(args.reference_image)
    arr = preprocess(image)
    device = hub.Device(args.target_device)
    log.info("Submitting cloud inference …")
    ijob = hub.submit_inference_job(
        model=quantized,
        device=device,
        inputs={"image": [arr]},
    )
    log.info("Inference job id: %s", ijob.job_id)
    ijob.wait()
    cloud_outputs = ijob.download_output_data()
    cloud_logits = list(cloud_outputs.values())[0][0]
    cloud_fake = softmax_fake_score(np.asarray(cloud_logits).reshape(1, 2))
    log.info("Cloud P(fake): %.4f", cloud_fake)

    # 2. Local PyTorch
    if args.checkpoint is not None:
        model = PramanaClassifier(backbone=args.backbone, pretrained=False)
        state = torch.load(args.checkpoint, map_location="cpu")
        model.load_state_dict(state.get("model", state))
        model.eval()
        with torch.no_grad():
            local_logits = model(torch.from_numpy(arr)).cpu().numpy()
        local_fake = softmax_fake_score(local_logits)
        log.info("Local  P(fake): %.4f", local_fake)
        delta = abs(local_fake - cloud_fake)
        log.info("Delta:          %.4f", delta)
        if delta > 0.05:
            log.warning("Delta > 0.05 — quantization likely degraded the model. Revisit calibration.")
        else:
            log.info("Quantization is within tolerance (delta ≤ 0.05).")


if __name__ == "__main__":
    _cli()
