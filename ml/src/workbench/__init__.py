"""Qualcomm AI Hub Workbench wrappers.

Bible §13 MANUAL: "Qualcomm AI Hub Workbench: model upload, compile target
selection, quantize job configuration. Done through the Workbench web UI and
the qai_hub_models CLI. Opus has limited knowledge of the exact incantations."

These scripts use the public qai_hub Python API and document the exact
incantations we expect to use. Run them yourself (with your API token
configured via `qai-hub configure --api_token ...`) — they DO talk to the
cloud.
"""
