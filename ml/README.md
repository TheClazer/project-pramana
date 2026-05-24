# ml/ — Engineer A's track (Python)

PyTorch training pipeline + AI Hub Workbench scripts. Output: two `.tflite`
files (INT8 + INT4) ready to drop into `android/app/src/main/assets/`.

## What's here

```
ml/
├── requirements.txt
├── src/
│   ├── data/
│   │   ├── datasets.py            FF++, Celeb-DF, IIIT-CFW loaders
│   │   └── sample_stand_in.py     small public sample so the pipeline runs
│   │                              before your dataset approvals land
│   ├── models/
│   │   └── backbones.py           MobileNet-V3-Small + EfficientNet-B0 + binary head
│   ├── rppg/
│   │   ├── pos.py                 POS algorithm (matches Kotlin port)
│   │   └── demo.py                webcam-recording demo runner
│   ├── workbench/
│   │   ├── compile.py             AI Hub compile job
│   │   ├── quantize.py            INT8 + INT4 quantize
│   │   ├── profile.py             cloud Snapdragon latency profile
│   │   └── inference.py           cloud inference sanity vs. local PyTorch
│   ├── train.py                   training loop
│   ├── eval.py                    AUC + accuracy on a held-out set
│   ├── reference_inference.py     THE preprocessing contract — Android must match
│   └── gradcam.py                 Grad-CAM heatmap generator (Stretch 1)
├── notebooks/
│   └── colab_train.ipynb          drop in Colab, hit Run All
└── tests/
    ├── test_pos.py
    └── test_reference_inference.py
```

## Quick start (no datasets approved yet)

```powershell
cd D:\Work\project-pramana\ml
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# Sanity-check the pipeline runs against a tiny public stand-in dataset
python -m src.train --dataset stand_in --epochs 1 --quick
python -m src.reference_inference --image src/data/_sample.jpg

# Run tests
pytest tests/
```

## Real training (after FF++ / Celeb-DF approvals)

```powershell
# 1. Drop the datasets into ml/datasets/ (gitignored)
#    ml/datasets/faceforensics/ ...
#    ml/datasets/celebdf/ ...
#    ml/datasets/iiitcfw/ ...

# 2. Train
python -m src.train --dataset combined --epochs 25 --backbone mobilenet_v3_small \
    --batch-size 64 --lr 3e-4 --out-dir runs/mobilenet_v3_small_v1

# 3. Eval — bible target: AUC > 0.85 on FF++ val split
python -m src.eval --checkpoint runs/mobilenet_v3_small_v1/best.pt --split val
```

## AI Hub Workbench (after `qai-hub configure --api_token ...`)

```powershell
# Compile the trained checkpoint to a deployable artifact
python -m src.workbench.compile --checkpoint runs/mobilenet_v3_small_v1/best.pt \
    --backbone mobilenet_v3_small

# Quantize — INT8 primary, INT4 secondary
python -m src.workbench.quantize --compile-job-id <id from above> --precision int8
python -m src.workbench.quantize --compile-job-id <id> --precision int4

# Profile on real cloud-hosted Snapdragon device (Bible §6 — the win moment)
python -m src.workbench.profile --quantize-job-id <int8-job-id> --device "Snapdragon 8 Gen 2"
python -m src.workbench.profile --quantize-job-id <int4-job-id> --device "Snapdragon 8 Gen 2"

# Sanity check: cloud inference matches local PyTorch within quantization tolerance
python -m src.workbench.inference --quantize-job-id <int8-job-id> \
    --reference-image src/data/_sample.jpg
```

Output `.tflite` files land in `runs/<backbone>/`. Copy to
`android/app/src/main/assets/pramana-int8.tflite` and `pramana-int4.tflite`.

## Bible refs

- §6  — Detection component, training targets, fusion rules
- §10 — Tech stack
- §13 — Vibe-safe (PyTorch training is GREEN; AI Hub Workbench is MANUAL)
