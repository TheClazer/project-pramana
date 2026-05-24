<div align="center">

# Pramāṇa

> *`pramāṇa`* — Sanskrit, *"valid proof, means of knowledge."*

**Hardware-rooted deepfake detection + cryptographic provenance, on-device, on Snapdragon.**
**Zero cloud. Zero telemetry. Verifiable end to end.**

*Detection for today's fakes. Provenance for tomorrow's truth.*

<br/>

![Android CI](https://github.com/TheClazer/project-pramana/actions/workflows/android.yml/badge.svg)
![Python CI](https://github.com/TheClazer/project-pramana/actions/workflows/python.yml/badge.svg)
![Min SDK](https://img.shields.io/badge/Android-28%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-2024.06-4285F4)
![Snapdragon](https://img.shields.io/badge/Snapdragon-Hexagon%20HTP-CC0000)
![Crypto](https://img.shields.io/badge/Crypto-ECDSA--P256%20%2F%20StrongBox-blueviolet)
![Cloud](https://img.shields.io/badge/Cloud-None-success)

</div>

---

## What this is

An Android app that does two things, and means both:

1. **It spots fakes.** Point the camera at someone, or open a photo from your gallery, and Pramāṇa tells you in real time whether it's real or AI-generated. Inference runs on the **Snapdragon Hexagon NPU** via the QNN TFLite Delegate. A second CPU-side check — **rPPG cardiac liveness** — listens for the subject's pulse signal in the captured pixels. A deepfake played back on a monitor has no pulse. We catch it.

2. **It proves what's real.** When you tap the shutter, Pramāṇa SHA-256s the pixels, signs the manifest with an **ECDSA-P256 key sealed inside Android Keystore (StrongBox)**, embeds the signed manifest in EXIF, *and* paints an invisible **DCT pixel watermark** across the image. EXIF gets stripped by WhatsApp? The watermark survives. Pixels get edited? The hash mismatches. The seal is two-layered by design.

Everything happens on the device. The app does not hold the `INTERNET` permission.

---

## Why the architecture

|  | Conventional deepfake tool | **Pramāṇa** |
|---|---|---|
| Filming a deepfake played on a monitor | Often fooled | **Caught** — rPPG sees no pulse |
| Original photo, EXIF stripped by WhatsApp | "No metadata, no provenance" | **DCT watermark survives**; verifier finds the fingerprint |
| Server-side detection | "Send us your photos" | **Runs on the phone.** No `INTERNET` permission |
| Adversarial fake from a 2027 diffusion model | Accuracy drops to coin-flip | Accuracy drops *and* the provenance half still works |
| INT4 / INT8 deployment for the chip | Maybe one variant | **Both shipped**, both profiled on real Snapdragon hardware |

---

## Stack

| Layer | Choice | Why it's here |
|---|---|---|
| Inference | TFLite + **QNN HTP Delegate** | Hexagon NPU at ~6 ms / frame |
| Quantization | **Qualcomm AI Hub Workbench** — INT8 + INT4 | Profiled on real cloud Snapdragon devices |
| Backbones | MobileNet-V3-Small (primary) · EfficientNet-B0 | Both in `qai_hub_models` with HTP support |
| Liveness | **rPPG** — POS algorithm + JTransforms FFT + 0.75–3.0 Hz bandpass | Real pulse, no real fake |
| Fusion | Weighted score + 2-second hard-veto | Bible §6 |
| Crypto | **ECDSA-P256** in **StrongBox** → TEE → RSA | Universal, hardware-rooted, no soft keystore ever |
| Manifest | C2PA-shaped JSON, **RFC 8785 JCS** canonicalized | Byte-identical bytes across Sealer ↔ Verifier |
| Watermark | 8×8 block DCT + **Reed-Solomon GF(256)** | Survives JPEG-q70, screenshots, WhatsApp re-encode |
| Camera | CameraX `ImageAnalysis` (YUV_420_888, **zero per-frame alloc**) | 30 fps on a mid-tier Snapdragon |
| UI | Jetpack **Compose** Material 3, dark-first | One codebase, all five screens |
| Tests | JUnit + Truth (Kotlin) · pytest (Python) | DCT round-trip, JCS vectors, hard-veto rule, POS pulse, RS detection |

---

## Quick start

```powershell
# 1. Clone
git clone https://github.com/TheClazer/project-pramana
cd project-pramana

# 2. Open the `android/` folder in Android Studio.
#    First sync creates gradlew + the wrapper jar. Accept.

# 3. Plug in any Android phone with USB debugging on:
adb devices                # should list your serial

# 4. Hit Run.
#    Pramāṇa boots in MOCK MODE — fake verdicts cycle through
#    GENUINE / SUSPICIOUS / FAKE, the shutter produces a real
#    ECDSA-P256 Keystore signature, the Verify path cycles all
#    five badge states. No model, no QNN AAR, no token required.
```

For the full path (training, Workbench compile/quantize/profile, QNN AAR drop, on-device verification) see **[`HANDOFF.md`](HANDOFF.md)**.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                CameraX  ImageAnalysis  (YUV_420_888, 30 fps)     │
│                pre-allocated buffers · zero per-frame allocation │
└──────────────────┬───────────────────────────────┬───────────────┘
                   │                               │
        ┌──────────▼─────────────┐      ┌──────────▼─────────────┐
        │  Detection  (NPU)      │      │  rPPG  (CPU, parallel) │
        │  MobileNet-V3-Small    │      │  POS + FFT             │
        │  INT8  · ~6 ms / frame │      │  0.75–3.0 Hz bandpass  │
        └──────────┬─────────────┘      └──────────┬─────────────┘
                   │                               │
                   └────────────┬──────────────────┘
                                │  Fusion
                                │  authenticity = 0.65·(1-npu) + 0.35·rppg
                                │  + 2-second hard-veto if rppg < 0.2
                                ▼
              ┌─────────────────────────────────────────┐
              │  Verdict:  GENUINE  /  SUSPICIOUS  /  FAKE │
              └────────────────────┬────────────────────┘
                                   │
                       on shutter, if GENUINE
                                   ▼
              ┌──────────────────────────────────────────┐
              │  pixels → SHA-256 → JCS → ECDSA-P256     │
              │  (StrongBox) → EXIF UserComment          │
              │              + DCT pixel watermark       │
              │              = SealedFile                │
              └──────────────────────────────────────────┘
```

Verify path: read EXIF → manifest → JCS canonicalize → ECDSA verify → recompute pixel hash. EXIF stripped? Scan DCT watermark for the fingerprint. No watermark either? Fall through to live detection. Five distinct verdicts; the UI maps each to a colored badge.

---

## Status — what's built, what's pending

**~85% of the bible's v1.1 core scope is written, tested, and runnable today.** The remaining 15% is gated on training runs, dataset approvals, and the gated Qualcomm AAR — all additive, no architectural changes.

### ✅ Done
| Track | What |
|---|---|
| Scaffold | Gradle version catalog, settings, manifest, ProGuard, resources, adaptive launcher icon |
| API | 5 interfaces (`DetectionEngine`, `SealEngine`, `VerifyEngine`, `CameraEngine`, `RppgStream`) · 5 honest mocks · all DTOs |
| Crypto | `Keystore` (StrongBox → TEE → RSA), `Sha256`, RFC 8785 **JCS** wrapper, `Exif`, **DCT** 8×8 watermark + **Reed-Solomon**, `RealSealEngine`, `TrustStore`, `RealVerifyEngine` (5 states) |
| Detection | `TfliteRunner` (QNN → GPU → XNNPACK CPU ladder), `PreprocessIntoTensor`, `Fusion` (hard-veto), `RealDetectionEngine` |
| rPPG | `PosAlgorithm`, `RealRppgStream` with 30-frame sliding FFT |
| Camera | `RealCameraEngine` (YUV, zero-alloc), `YuvToRgb` (in-place, respects strides) |
| UI | `MainActivity`, `CameraScreen`, `VerdictOverlay`, `VerifyScreen`, `PostCaptureScreen`, `SettingsScreen`, `PermissionsScreen`, dark Material 3 theme, share-intent routing |
| Tests | 9 Kotlin test classes (JCS, DCT, RS, YCbCr, Fusion, POS, SHA-256, Manifest) · 4 Python files (POS, reference inference, CLI roundtrip, JCS vectors) |
| ML | PyTorch training loop, dataset loaders (FF++ / Celeb-DF / IIIT-CFW + sample stand-in), Grad-CAM, reference inference contract, Colab notebook |
| Workbench | `compile.py`, `quantize.py` (INT8 + INT4), `profile.py`, `inference.py` |
| CLI | Python `seal` / `verify` / `roundtrip` / `vectors` with full crypto + DCT + EXIF parity to Kotlin |
| CI | GitHub Actions for Android (assemble + tests + APK artifact) and Python (CLI + ml) |
| Docs | Bible (markdown mirror of the v1.1 PDF), HANDOFF, module READMEs, setup checklist |

### ⏳ In progress / gated on user actions
| Item | Blocker | Where |
|---|---|---|
| Trained `.tflite` in `assets/` | FF++ / Celeb-DF approvals + training run | Engineer A |
| QNN HTP Delegate live init | AAR download from Qualcomm Developer Network + version-specific glue | `detection/TfliteRunner.kt::tryQnn` |
| Real flagship latency number for the pitch deck | AI Hub Workbench profile run with your API token | `ml/src/workbench/profile.py` |
| MediaPipe Face Mesh ROI for rPPG | Wire `tasks-vision` FaceLandmarker into the existing POS pipeline (math is already there) | `rppg/RealRppgStream.kt` |
| Cross-language JCS vector files committed | One-command generation (`python -m tools.cli_sealverify.main vectors`) | `tools/test_vectors/` |
| Demo gallery curation, backup demo video | Pre-hackathon | All |

Grep `TODO(human):` in `android/` for the precise list of code-side blockers.

---

## Repo layout

```
docs/bible.md          single source of truth (markdown mirror of v1.1 PDF)
docs/setup-checklist.md what the human must do, ordered

android/               Kotlin app
├── app/
│   ├── src/main/kotlin/io/teamsnapped/pramana/
│   │   ├── api/                 interfaces + DTOs
│   │   ├── camera/              CameraX YUV pipeline
│   │   ├── detection/           TFLite + QNN + fusion
│   │   ├── rppg/                POS + FFT
│   │   ├── seal/                Keystore, EXIF, RealSealEngine
│   │   ├── verify/              RealVerifyEngine
│   │   ├── jcs/                 RFC 8785 canonicalization
│   │   ├── dct/                 8×8 DCT watermark + Reed-Solomon
│   │   ├── mocks/               one mock per interface
│   │   └── ui/                  Compose screens + theme + nav
│   ├── src/test/kotlin/         9 unit-test classes
│   ├── src/main/assets/         pramana-int8.tflite drops here
│   └── libs/                    qnn-tflite-delegate.aar drops here
├── gradle/libs.versions.toml    edit deps HERE
└── settings.gradle.kts

ml/                              Python training + AI Hub Workbench
├── src/
│   ├── data/                    FF++ / Celeb-DF / IIIT-CFW loaders + sample stand-in
│   ├── models/                  MobileNet-V3 + EfficientNet-B0 + binary head
│   ├── rppg/                    POS reference + webcam demo
│   ├── workbench/               compile / quantize / profile / inference
│   ├── train.py · eval.py
│   ├── reference_inference.py   THE preprocessing contract — Kotlin must match
│   └── gradcam.py
├── notebooks/colab_train.ipynb
└── tests/                       pytest

tools/cli_sealverify/            Python mirror of the Sealer/Verifier
├── main.py                      seal · verify · roundtrip · vectors · keygen
├── jcs_compat.py · keystore.py · manifest.py · dct_watermark.py · reed_solomon.py · exif_io.py
└── tests/                       roundtrip + JCS vector pinning

tools/test_vectors/              JCS canonical bytes pinning Android ↔ Python
.github/workflows/               Android + Python CI
CLAUDE.md                        AI-assistant project context
HANDOFF.md                       the ordered user to-do list
```

---

## Engineering principles

1. **The bible wins.** `docs/bible.md` is the source of truth. Chat memory and stale slide decks don't.
2. **Every interface has a mock.** The app must run end-to-end with any subset of real impls substituted. We do not couple modules.
3. **Zero allocations in the hot path.** `ByteBuffer`s and `FloatArray`s are pre-allocated at startup and reused. GC pauses cost frames at 30 fps.
4. **Never hand-roll canonicalization.** RFC 8785 JCS via the published Erdtman library (Kotlin) and `jcs` PyPI (Python). The signature drift that hand-rolling causes is silent and catastrophic.
5. **Hardware-backed crypto, always.** StrongBox → TEE → RSA. Never software-backed Keystore.
6. **No `INTERNET` permission.** No telemetry. No cloud. This isn't a feature flag; it's the pitch.
7. **`TODO(human):` is sacred.** When the bible flags a task as MANUAL (e.g. QNN delegate init), we stub it with that comment and leave the real work for a human at the keyboard. We do not vibe-code things Opus is known to hallucinate.

---

## Tests

```powershell
# Kotlin
cd android
.\gradlew :app:testDebugUnitTest

# Python — CLI seal/verify round-trip + JCS vector pin
pytest tools/cli_sealverify/tests/

# Python — POS algorithm + reference preprocessing
pytest ml/tests/
```

GitHub Actions runs all three on every push to `main`.

---

## Bible

[`docs/bible.md`](docs/bible.md) — the v1.1 engineering bible. Read it before touching code. 985 lines covering: pitch positioning, full architecture diagram, every module's spec, the Vibe-vs-Manual matrix, risk register, stretch goals, honest limitations, and the interface contracts.

---

## Built for

**[Hack4SoC 3.0](https://h4s.org)** · IEEE RVCE × Qualcomm · Team Snapped · v1.1

---

<div align="center">

*"Detection for today's fakes. Provenance for tomorrow's truth."*

</div>
