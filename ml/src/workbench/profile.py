"""Submit a latency profile job to AI Hub Workbench on a real cloud-hosted
Snapdragon device.

Bible §6 — "Submit a profile job for each variant. Workbench runs your
model on a real cloud-hosted Snapdragon device and returns per-operator
latency. This is the latency number you cite in your pitch."

The pitch slide cites the wall-clock latency from this run. Save the JSON
output and screenshot the dashboard.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def _cli():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quantize-job-id", required=True)
    ap.add_argument("--target-device", default="Snapdragon 8 Gen 2")
    ap.add_argument("--out-json", type=Path, default=Path("runs/workbench/profile.json"))
    args = ap.parse_args()

    try:
        import qai_hub as hub
    except ImportError:
        log.error("qai-hub not installed. Run: pip install qai-hub")
        sys.exit(2)

    args.out_json.parent.mkdir(parents=True, exist_ok=True)

    quantize_job = hub.get_job(args.quantize_job_id)
    quantized = quantize_job.get_target_model()
    device = hub.Device(args.target_device)

    log.info("Submitting profile job on %s …", args.target_device)
    pjob = hub.submit_profile_job(model=quantized, device=device)
    log.info("Profile job id: %s", pjob.job_id)
    log.info("Dashboard:      %s", pjob.url)
    pjob.wait()

    profile = pjob.download_profile()
    args.out_json.write_text(json.dumps(profile, indent=2))
    log.info("Profile written to %s", args.out_json)

    # Pull out the headline number for the pitch
    try:
        execution = profile["execution_summary"]
        log.info("=" * 60)
        log.info("LATENCY:        %.2f ms",       execution["estimated_inference_time"] / 1000.0)
        log.info("FIRST INFER:    %.2f ms",       execution.get("first_inference_time", 0) / 1000.0)
        log.info("PEAK MEMORY:    %.2f MB",       execution.get("inference_memory_peak_range", [0, 0])[1] / 1e6)
        log.info("COMPUTE UNIT:   %s",            execution.get("compute_unit_execution_time", {}))
        log.info("=" * 60)
        log.info("Cite the LATENCY line in the pitch deck.")
    except (KeyError, IndexError):
        log.warning("Profile JSON shape unexpected; inspect %s manually.", args.out_json)


if __name__ == "__main__":
    _cli()
