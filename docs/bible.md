# PRAMĀṆA

> *pramāṇa · n. Sanskrit, 'valid proof, means of knowledge.'*

**Hardware-rooted on-device deepfake detection and authenticity provenance, built on Snapdragon.**

Engineering Bible · Team Snapped · Hack4SoC 3.0 · IEEE RVCE × Qualcomm
**v1.1** · revised after hardening review

*Detection for today's fakes. Provenance for tomorrow's truth.*

> **Source of truth.** This file is the markdown mirror of `Pramana_Bible_TeamSnapped_v1.1.pdf`. When a decision changes, update this file in the same commit. The PDF is the v1.1 snapshot; this file is the living version.

---

## How to use this document

This document is the single source of truth for everything Team Snapped is building for Hack4SoC 3.0. It exists so that no engineer on the team needs to ask 'what was the plan again?' during the 20-day prep or the 24-hour hackathon. Read it once cover-to-cover, then refer back as needed.

Three rules govern how to use it:

- **Default to the bible.** If something contradicts what you remember from a chat or a meeting, the bible wins. Update the bible to reflect new decisions.
- **Each engineer owns their section.** If you're the Detection engineer, Section 6 is yours. Treat it as your spec.
- **Independence is non-negotiable.** Every component is designed so that if it fails, the rest of the app keeps working. Don't add tight couplings.

---

## Changelog — v1.1

This version hardens v1.0 against three real attack surfaces and one execution risk identified during external review. The architecture did not change. Three components moved from stretch to core, one engineering rule was pinned to a standard, and one team role got a small handoff. No promises got bigger; one promise got more honest.

- **rPPG cardiac liveness — promoted from stretch to core.** Closes the screen-capture loophole: filming a deepfake on a monitor now fails the biological liveness check because the subject has no pulse signal in the captured pixels.
- **DCT invisible pixel watermark — promoted from stretch to core.** Closes the EXIF-stripping loophole: every consumer messaging app strips EXIF, so manifest-in-metadata alone is fragile. The pixel-domain watermark survives compression, screenshots, and platform recompression, carrying a fingerprint that lets verifiers locate the manifest separately.
- **JSON canonicalization pinned to RFC 8785 (JCS).** Prevents silent signature drift between Sealer and Verifier caused by floating-point or key-order serialization differences. All numeric metadata fields stringified.
- **Camera output format: `YUV_420_888`, not `RGBA_8888`. No per-frame allocations.** Removes a memory-churn risk that would have cost frames at 30 fps. Pre-allocated `ByteBuffer`s reused on every frame.
- **Engineer B picks up UI-integrator role from day 17.** 'Frontend by committee' fails — someone has to own the Compose codebase as the integration phase begins. B already owns the Android skeleton, so this is a natural extension, not a new role.
- **New deliverable for Engineer B: borrow a Snapdragon phone during prep.** Even a few hours of real-device APK testing dramatically lowers hackathon-day QNN delegate risk.

---

## Contents

1. [Executive summary](#1-executive-summary) — What we're building, in 90 seconds
2. [The product in plain language](#2-the-product-in-plain-language) — For non-technical readers
3. [Problem statement mapping](#3-problem-statement-mapping) — How we hit every line of the brief
4. [System architecture](#4-system-architecture) — Full pipeline, capture and verify paths
5. [Module boundaries and independence](#5-module-boundaries-and-independence) — Why no single failure breaks the app
6. [Component: Detection pipeline](#6-component-detection-pipeline) — Engineer A's spec
7. [Component: Cryptographic sealing](#7-component-cryptographic-sealing) — Engineer C's spec
8. [Component: Verification pipeline](#8-component-verification-pipeline) — Shared logic across A and C
9. [Mobile platform and camera](#9-mobile-platform-and-camera) — Engineer B's spec
10. [Complete tech stack](#10-complete-tech-stack) — Every library, model, tool
11. [Redundancy and failover](#11-redundancy-and-failover) — What happens when each piece fails
12. [Team distribution: three engineers, parallel tracks](#12-team-distribution-three-engineers-parallel-tracks) — Who does what
13. [Vibe-code vs manual matrix](#13-vibe-code-vs-manual-matrix) — Where Opus 4.7 helps, where it hurts
14. [Risk register](#14-risk-register) — Top risks with pre-decided mitigations
15. [Stretch goals](#15-stretch-goals) — Priority-ordered, attempt only after core lands
16. [Honest limitations](#16-honest-limitations) — What we explicitly do not claim
17. [Appendix A: Interface contracts and code templates](#17-appendix-a-interface-contracts-and-code-templates)
18. [Appendix B: Glossary](#18-appendix-b-glossary)

---

## 1. Executive summary

### What we're building

An Android plugin app called **Pramāṇa** that does two things: it detects deepfakes in real time on the device's camera and gallery, and it cryptographically seals genuine captures at the moment of shutter so their authenticity can be verified later.

Detection is a two-stream pipeline — a deepfake classifier running on the Snapdragon Hexagon NPU via the QNN TFLite Delegate, paired with a CPU-side rPPG cardiac liveness check that detects whether the subject in front of the camera has an actual pulse.

Sealing uses an Ed25519 (or ECDSA-P256) key sealed inside the Snapdragon Secure Element via Android Keystore, and writes a two-layer authenticity stamp: a manifest in EXIF metadata and an invisible perceptual watermark in the pixel data itself, so the seal survives social-media compression that strips metadata.

Everything runs on-device. No cloud, no telemetry, no data leakage.

### How we win the room

Four positioning angles, all defensible:

- **Qualcomm-native top to bottom.** Backbone from `qai-hub-models`. Compile, quantize (INT8 and INT4), and on-device profile via AI Hub Workbench before the hackathon. Deploy via QNN TFLite Delegate. Android Keystore with StrongBox flag for hardware-backed crypto. Every link in the chain is a Qualcomm-blessed component.
- **Dual system, not single detector.** Most teams will ship a detector that asks 'does this look fake?' Pramāṇa also ships provenance — proof that a capture is real. Detection plus provenance is what the problem statement actually asked for, and almost no team will implement both well.
- **Two-stream detection with orthogonal failure modes.** The classifier asks 'does this look fake?' The rPPG liveness check asks 'is there a real human heartbeat in this video?' A deepfake video played on a screen and recorded by our camera fails the second check even if it passes the first.
- **Two-layer watermark survives platform stripping.** EXIF manifest (rich, structured) plus invisible DCT pixel watermark (survives WhatsApp, Instagram, screenshotting). Removes the obvious 'just strip the metadata' attack.
- **Ships INT4 and INT8.** The problem statement explicitly says 'INT4/INT8 quantized detection models.' We ship both variants with benchmarked latency. Most teams will ship one.

### Honest scope

We are shipping a working demo, not a production product. The detection model is a fine-tuned MobileNet/EfficientNet backbone trained on FaceForensics++ and Celeb-DF — strong against the demos we curate, weaker against novel adversarial fakes from modern diffusion-based generators. rPPG works well on face video with visible skin and good lighting; it is weaker on still images, partial faces, and very dark skin under fluorescent venue lighting. The provenance manifest is C2PA-shaped but lives inside EXIF plus a DCT watermark, not full JUMBF — the latter remains a stretch goal. The signature scheme is real and hardware-backed; the verifier is in-app rather than the global C2PA ecosystem. Everything we claim, we deliver. Anything we cannot deliver inside 24 hours plus 20 days of prep, we mark as roadmap and do not promise.

---

## 2. The product in plain language

Pramāṇa is an Android app that protects you from deepfakes in two ways.

**One — it spots fakes.** Point your camera at someone, or open a photo or video that someone sent you, and the app tells you in real time whether it is real or AI-generated. All the analysis happens on your phone. Nothing gets uploaded. Nothing leaves the device.

**Two — it proves what is real.** When you take a photo or video with Pramāṇa, the app stamps it at the moment of capture with a tamper-proof digital signature, generated by a security chip locked inside your phone. If anyone later edits or fakes that photo, the seal breaks and the app catches it. If they share the original untouched, anyone with Pramāṇa can verify it came from a real camera at a real time on a real device.

So it is both a shield and a stamp. The shield catches lies. The stamp proves the truth.

You would want it if you are a journalist publishing photos from the ground, a parent worried about what your kids see on WhatsApp, a person whose face has been deepfaked into something harmful, or anyone who is tired of not knowing whether what is on their screen actually happened.

It works without internet. It does not see your data. It runs on the chip in your phone, not a server somewhere.

> **In one line: Pramāṇa is the difference between 'this looks real' and 'this is provably real.'**

---

## 3. Problem statement mapping

Every requirement from the published problem statement, mapped to the part of Pramāṇa that satisfies it.

| Brief requirement | How Pramāṇa satisfies it |
|---|---|
| Camera or gallery plugin | Single Android app exposing both a camera capture surface and a gallery import / share-intent surface. |
| Utilizes Snapdragon ISP and NPU pipeline | CameraX feeds the ISP-processed YUV stream into an analysis pipeline that runs inference on the Hexagon NPU via the QNN TFLite Delegate. |
| Intercepts and analyzes frames pre-display | Frames are tapped via CameraX `ImageAnalysis` at 30 fps before the preview is presented; the verdict overlay is composited on top of the preview. |
| Leverages the QNN SDK | We deploy through the QNN TFLite Delegate, which is the QNN SDK's officially recommended path for TFLite models. The raw QNN SDK with `.dlc` is a fallback option. |
| Lightweight INT4 / INT8 quantized models at 30 fps | We ship two model variants — INT8 (activation quantized) and INT4 (weight-compressed), both produced by AI Hub Workbench. Latency profiled on real Snapdragon hardware in the cloud before the hackathon. |
| Privacy-first architecture, no cloud-based data leaks | Zero network calls in the production code path. The keystore-signed manifest is generated and embedded entirely on-device. All inference is local. |
| Cryptographic authenticity watermark at moment of capture | On shutter, the system computes SHA-256 of the pixels plus metadata, signs it with Ed25519 from a StrongBox-backed Keystore, and embeds the signed manifest in EXIF `UserComment` (image) or the equivalent metadata atom (video). |
| Verified trail of media integrity | Verify mode reads the embedded manifest, verifies the signature, recomputes the content hash, and surfaces one of three states: `VERIFIED ORIGINAL`, `VERIFIED-BUT-MODIFIED`, or `BROKEN SEAL`. |

---

## 4. System architecture

Two end-to-end paths share a common module stack. Path A is the capture path that ingests live camera frames; path B is the verify path that ingests files brought in from outside the app.

### Capture path

```
CameraX.ImageAnalysis (YUV_420_888 @ 30 fps, pre-allocated buffers)
        |
        +------------------------------+
        |                              |
        v                              v
Face Detector (NPU)            rPPG cardiac stream (CPU, parallel)
-- crop ROI -->                30-frame sliding window
        |                              POS + FFT in 0.75-3 Hz band
        v                              |
Deepfake Classifier (HTP, INT8)        |
        |                              |
        +--------- fusion -------------+
                       |
                       v
        Verdict bus: GENUINE / SUSPICIOUS / FAKE
                       |
   ---------+---------+
            |         |
            v         v
   Live overlay   On shutter (GENUINE only):
   (badge +       SHA-256 -> Ed25519 sign
    heatmap)      -> embed manifest in EXIF
                  -> embed DCT watermark in pixels
                  -> save authenticated file
```

### Verify path

```
Gallery import / Share-intent
        |
        v
EXIF reader -> Pramana manifest present?
     |                          |
   yes                          no -> DCT watermark scanner
     |                          |
     |                  +-------+-------+
     |                  |               |
     |              watermark      no watermark
     |              found             found
     |                  |               |
     |              fetch manifest      v
     |              by fingerprint   Run classifier (NPU + rPPG)
     |                  |            -> show verdict + heatmap
     v                  v
Signature verify + hash recompute
     |
     v
VERIFIED ORIGINAL  |  VERIFIED-BUT-MODIFIED  |  BROKEN SEAL
```

### Module map

The system is split into four logically independent modules. The arrows show data flow, not coupling. Each module exposes a stable interface (see Appendix A) and can be substituted with a mock during development.

```
+----------------+      +------------------+      +---------------------+
| CameraEngine   | ---> | DetectionEngine  | ---> | VerdictBus          |
| (Engineer B)   |      | (Engineer A)     |      | (shared)            |
+----------------+      +------------------+      +----------+----------+
                                                             |
                                                             v
                          +------------------+      +---------------------+
                          | SealEngine       | <--- | UI / OverlayRenderer|
                          | (Engineer C)     |      | (built collectively)|
                          +------------------+      +---------------------+
                                  |
                                  v
                          +------------------+
                          | VerifyEngine     |
                          | (Engineer C)     |
                          +------------------+
```

---

## 5. Module boundaries and independence

The single most important architectural rule: **if any one module fails, the rest of the app still demos.** We achieve this through three patterns.

### Pattern 1 — Stable interfaces with mocks

Each module exposes a Kotlin interface. Each interface has both a real implementation and a mock implementation. The app can run end-to-end with any subset of mocks substituted for real implementations. During the 20-day prep, you should be running the app on a regular Android phone with all real implementations except `DetectionEngine` (mocked until the model is ready).

Interfaces (full signatures in Appendix A):

- `DetectionEngine.analyze(frame): Verdict`
- `SealEngine.seal(file, captureMeta): SealedFile`
- `VerifyEngine.verify(file): VerifyResult`
- `CameraEngine.start(onFrame) / stop()`

### Pattern 2 — Hard fallbacks at every layer

Each module owns its own backend fallback ladder. The app code never sees the fallback logic — it just calls the interface.

| Module | Primary | First fallback | Last-resort fallback |
|---|---|---|---|
| DetectionEngine | TFLite + QNN HTP delegate | TFLite + GPU delegate | TFLite on CPU (XNNPACK) |
| SealEngine | Keystore Ed25519 with StrongBox flag | Keystore Ed25519 with TEE backing | Keystore RSA (every Android has this) |
| VerifyEngine | Manifest signature verify + hash recompute | Manifest read-only (skip cryptographic verify) for malformed manifests | Fall through to live detection |
| CameraEngine | CameraX ImageAnalysis at 30 fps | CameraX at 15 fps (reduced load) | Static gallery-only mode (no live preview) |

### Pattern 3 — Demo switch

A hidden config flag (`--demo-mode`) substitutes known-good values for any live measurement that is unreliable on stage. Detection scores can be supplied from a pre-recorded fixtures file. The signing path stays real even in demo mode — only readouts that depend on stochastic live conditions get pinned. This is the insurance policy, not the default.

> **Hard rule.** Demo mode is for stage rescue only. Never default to it during development. If you find yourself reaching for demo mode during the build, your real implementation is broken and you need to fix it, not paper over it.

---

## 6. Component: Detection pipeline

**Owner:** Engineer A — Detection & Model

### Goal

Take a face crop (224×224 RGB tensor) and return a deepfake probability score and an attention heatmap, in under 10 ms per inference on the Hexagon NPU. The interface to the rest of the app is: feed in a frame, get back a `Verdict` object. Nothing else in the app should know or care what model is running or how it is quantized.

### Model selection

Backbone candidates (in priority order): **MobileNet-V3-Small first, EfficientNet-B0 second.** Both are present in the `qai-hub-models` catalogue with confirmed HTP support, meaning their operator sets compile cleanly to Hexagon. MobileNet-V3-Small is the safer choice — smaller, faster, more predictable in quantization. EfficientNet-B0 gives slightly higher accuracy but a marginally trickier quantization profile. Train both during prep; pick the one with better INT8 accuracy after quantization.

Both backbones get a swapped-in binary classification head trained on:

- **FaceForensics++ (c23 compression)** as the primary corpus — broad coverage of face-swap, face-reenactment, and neural-texture fakes.
- **Celeb-DF-v2** as a hard-fakes augmentation — pushes the model against higher-quality synthetic faces.
- **Indian face augmentation** — roughly 500 real Indian faces from IIIT-CFW and curated public-domain sources. Counters the Western-centric bias of FF++. This is what we cite when judges ask about dataset bias.

### Training, the boring path

Fine-tune the chosen backbone on the combined dataset with a binary cross-entropy head. Target: AUC > 0.85 on the FF++ validation split, > 0.75 on Celeb-DF. Anything below that and we are going to embarrass ourselves on stage, so retrain. Save checkpoints to a Google Drive folder owned by Engineer A.

### AI Hub Workbench — the win

This is where Pramāṇa stops looking like a student project and starts looking Qualcomm-native. Once the PyTorch checkpoint is ready, do not manually fight with QNN SDK conversion scripts. Instead:

- Install `qai_hub_models` and configure your AI Hub Workbench API token.
- Wrap the trained model in the `qai_hub_models` export pattern, choose target device family (Snapdragon 8 Gen 2 or 8 Gen 3 — confirm which device the hackathon hands out, then target one tier above for safety).
- Submit a **compile** job. Workbench compiles the model into a deployable artifact.
- Submit a **quantize** job — once with INT8, once with INT4. Receive two compiled `.tflite` files (with HTP delegate metadata baked in).
- Submit a **profile** job for each variant. Workbench runs your model on a real cloud-hosted Snapdragon device and returns per-operator latency. This is the latency number you cite in your pitch.
- Submit an **inference** job to verify outputs match the local PyTorch output to within quantization tolerance. If they diverge by more than a few percent, the quantization broke and you need to revisit calibration.

> **Win moment.** When a judge asks 'how do you know this runs at X ms on Snapdragon?' — you answer: *'we profiled it on Snapdragon hardware in the cloud through Qualcomm's own AI Hub Workbench before this hackathon began.'* Slide of the profiler output ends the question.

### INT8 vs INT4 — ship both

The problem statement explicitly says 'INT4/INT8.' Shipping both variants with benchmarked numbers is a checkbox that most teams will miss because they only need one to demo. Build INT8 as the primary (better accuracy retention), INT4 as the secondary (smaller, faster, slightly lower accuracy). Show both numbers in the pitch slide.

### On-device runtime

The deployment path is the **QNN TFLite Delegate**. Engineer B integrates this; Engineer A delivers a working `.tflite` file plus a Python reference that runs the exact same preprocessing and postprocessing as the Android app expects. The Python reference is the contract — if the Android implementation produces different numbers, the Android implementation is wrong.

### Explainability

Grad-CAM heatmaps generated from the model's last convolutional layer. Only rendered on tap or on verdict change, never every frame — too expensive. Heatmap data is a 7×7 or 14×14 grid that the UI layer upscales and overlays with alpha blending on the face crop region.

### Stream B — rPPG cardiac liveness (core, not stretch)

rPPG (remote photoplethysmography) extracts a pulse signal from subtle skin-color oscillations captured on camera. Living human skin pulses at 0.5 to 3 Hz from capillary blood flow; deepfake-generated faces and re-recordings of screens do not. This is the signal that closes the screen-capture loophole — filming a deepfake on a monitor produces no biological pulse, so the fusion layer flags it as `SUSPICIOUS` regardless of what the visual classifier thinks.

Pipeline, all CPU, runs in parallel with NPU inference:

- MediaPipe Face Mesh → 6 fixed ROI patches: forehead, both cheeks, nose bridge, two jawline points (~1 ms / frame).
- 30-frame sliding RGB buffer per ROI (1 second of video at 30 fps).
- **POS (Plane-Orthogonal-to-Skin)** algorithm projects the RGB signal onto a skin-tone-invariant plane, isolating the pulsatile component.
- FFT on the projected signal, bandpass filter to 0.75–3.0 Hz (45–180 BPM physiological range).
- SNR computed as `(power_in_heartband / total_power)`, clamped to `[0, 1]`. Strong pulse → high score.

**Hard-veto rule:** if rPPG SNR is below 0.2 on a clear face video with valid landmarks for more than 2 consecutive seconds, the fusion layer forces the verdict to `SUSPICIOUS` minimum, regardless of the classifier output. Biology overrides pixels.

**Honest limitations:** rPPG requires a face video (not still images) with visible skin, decent lighting, and stable camera framing. It is slightly weaker on very dark skin tones under bad lighting. When MediaPipe landmark confidence drops below 0.7, the rPPG stream marks itself unavailable and the fusion layer falls back to classifier-only. This is graceful degradation, not failure.

### Fusion layer

Two streams combine into a single authenticity score. The classifier produces a manipulation probability (higher = more likely fake). The rPPG stream produces a liveness SNR (higher = more confident pulse). For a video with a clearly visible face under reasonable conditions:

```kotlin
authenticity = 0.65 * (1 - npu_score) + 0.35 * rppg_score   // when rPPG available
authenticity = 1.0  * (1 - npu_score)                       // when rPPG unavailable
                                                            // (still photo, no face,
                                                            //  low landmark confidence)

if (rppg_available && rppg_score < 0.2 for > 2s) {
    authenticity = min(authenticity, 0.35)   // hard veto
}

label =
    if (authenticity > 0.7) GENUINE
    else if (authenticity > 0.3) SUSPICIOUS
    else FAKE
```

### Module interface (the contract)

```kotlin
interface DetectionEngine {
    suspend fun analyze(frame: FrameInput): Verdict
    fun isReady(): Boolean
    fun backend(): String  // 'NPU' | 'GPU' | 'CPU'
}

data class FrameInput(val rgb: ByteArray, val width: Int, val height: Int)

data class Verdict(
    val label: VerdictLabel,           // GENUINE | SUSPICIOUS | FAKE
    val confidence: Float,             // 0.0 .. 1.0
    val heatmap: FloatArray?,          // optional, 7x7 or 14x14 flattened
    val latencyMs: Int,
    val backend: String
)
```

### What can fail and what Engineer A does about it

| Failure | Mitigation |
|---|---|
| INT8 quantization drops accuracy below threshold | Switch primary backbone (MobileNet ↔ EfficientNet). Re-quantize with extended calibration set (500+ images instead of 200). |
| INT4 quantization unusable on chosen backbone | Ship INT8 only, present INT4 as 'attempted, INT4 variant available in the next iteration.' Honest framing wins points. |
| QNN HTP delegate fails on hackathon device | Engineer B falls back to GPU delegate then CPU. Engineer A's deliverable is unchanged — the `.tflite` file works on all backends, just slower on the fallbacks. |
| Model accuracy embarrasses on a judge's deepfake | Engineer A curates the demo gallery beforehand from known-detectable FF++ and Celeb-DF samples. Live judge-supplied deepfakes are honestly framed as 'best-effort.' |

---

## 7. Component: Cryptographic sealing

**Owner:** Engineer C — Provenance & Cryptography

### Goal

When the user captures a photo or video with Pramāṇa and the live verdict says `GENUINE`, the file gets sealed at the moment of save. Sealing means: a cryptographic manifest is generated, signed with a hardware-backed key, and embedded inside the file's metadata so that any verifier later can prove the file is unmodified and originated from this device.

### Key management

On first launch, Pramāṇa generates an Ed25519 keypair using `KeyGenParameterSpec.Builder` with `setIsStrongBoxBacked(true)`. The private key is sealed inside the Snapdragon Secure Element / TrustZone and is non-extractable — neither the app, the OS, nor a root user can pull the raw key bytes out. The public key is exposed via the Keystore for verification by the same device or by a trust store. On devices without StrongBox (rare for modern Snapdragon flagships), fall back to TEE-backed Keystore, which is still hardware-isolated. On devices without TEE (should not happen on Snapdragon), fall back to RSA.

```kotlin
// Engineer C — key generation, run once at first launch
val keyGenParameterSpec = KeyGenParameterSpec.Builder(
    "pramana_signing_key",
    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    .setIsStrongBoxBacked(true)              // primary
    .setUserAuthenticationRequired(false)
    .build()
// Catch StrongBoxUnavailableException -> retry without setIsStrongBoxBacked
// Note: Android Keystore Ed25519 support requires API 33+; if unavailable use ECDSA P-256
```

> **Pragmatic note.** Android Keystore's Ed25519 support is API 33+ and not universal. The safer compatibility choice is ECDSA P-256, which every modern Android device supports natively in hardware Keystore. Engineer C should default to ECDSA P-256 and only attempt Ed25519 if the issued device supports it cleanly. The pitch language 'hardware-backed asymmetric signature' covers both — judges will not interrogate the curve choice.

### Manifest schema

The manifest is a JSON object modeled on the C2PA Content Credentials schema but simplified for EXIF embedding. C2PA full compliance (JUMBF container + JSON-LD claims + assertion store) is a stretch goal. The schema we ship in the core build:

```json
{
  "version": "1.0",
  "generator": "Pramana/1.0",
  "captured_at": "1718368472103",
  "device_fingerprint": "<sha256>",
  "sensor": {
    "model": "<from Build.MODEL>",
    "iso": "100",
    "exposure_us": "8333",
    "focal_length_mm": "4.38"
  },
  "content_hash": "<sha256 of pixel bytes>",
  "detection": {
    "score": "0.04",
    "label": "GENUINE",
    "model": "pramana-mobilenet-v3-small-int8-v1",
    "backend": "NPU"
  },
  "key_id": "<sha256 of public key>",
  "signature": "<base64 of ECDSA-SHA256 over canonicalized JSON above>"
}
```

**Canonicalization rule (v1.1 — pinned to a standard):** the signed bytes are the **RFC 8785 (JSON Canonicalization Scheme — JCS)** serialization of all fields above except `signature`. JCS handles key ordering, number formatting, and string escaping deterministically across implementations. Use a published JCS library (one for Kotlin/Java, one for Python — both have reference implementations). **All numeric metadata fields are stored as strings in the manifest** (e.g. `"focal_length_mm": "4.38"`) to remove any remaining float-formatting risk.

Both Sealer and Verifier import the same library and call it identically. **Test vectors live in the repo:** three sample manifests, their JCS bytes, their expected signatures with a fixed test key. Any drift from these test vectors means the canonicalization is wrong; fix it before integrating.

### Embedding

For images, the manifest is base64-encoded and written to the EXIF `UserComment` field (tag `0x9286`). For videos, the equivalent location is a custom box in the MP4 metadata atom — a `moov/udta` sub-atom named `pmna`. EXIF library: `androidx.exifinterface` (the official Android one, not the third-party fork). MP4 box writing: `mp4parser` or a hand-rolled small writer for the `udta` box.

### Layer 2 — invisible DCT pixel watermark (core, not stretch)

EXIF metadata gets stripped by every consumer messaging app — WhatsApp, Signal, Instagram, X all do it during compression or upload. If we only embed the manifest in EXIF, the second a Pramāṇa-signed photo passes through WhatsApp it becomes indistinguishable from any unprovenanced image. The DCT pixel watermark fixes this by embedding a small fingerprint directly in the image pixels, which survives compression, screenshotting, and most platform recompression.

What the DCT watermark carries (small payload, ~256 bits):

- Pramāṇa version byte.
- First 16 bytes of the manifest SHA-256 (so a verifier knows to look for the manifest with this fingerprint).
- First 8 bytes of the key fingerprint (so a verifier knows which public key to use).
- Error-correcting redundancy bits (Reed-Solomon over ~half the payload — survives partial pixel corruption).

Embedding algorithm (classical, well-documented, ~150 lines of Kotlin):

- Convert image to YCbCr, work on the Y (luminance) channel only — survives chroma subsampling.
- Partition into 8×8 pixel blocks (same blocking as JPEG, which is why this survives JPEG recompression).
- Apply 2D DCT on each block.
- For each block, modify the coefficient at a chosen mid-frequency position (zigzag index ~20–40) by ±delta to encode one bit. Mid-frequencies survive JPEG quality 70+ but are imperceptible to the eye.
- Inverse DCT, reassemble image.
- Embed runs on the saved file (post-shutter), not on every preview frame — no 30-fps pressure.

Verification scans an incoming image for the watermark by repeating the DCT and reading back the embedded bits. If the watermark is found, the manifest fingerprint tells the verifier which manifest to look up — either from a local trust store, from a sidecar file, or (future) from a network registry. If the manifest is unavailable but the watermark is intact, the file is at least marked 'claimed Pramāṇa origin, manifest unavailable.'

> **Two layers, two attacks.** Stripping EXIF removes the manifest. The DCT watermark still proves a Pramāṇa origin claim. Stripping both requires either heavy editing (detected) or a deliberate pixel-level attack (forensically obvious). The combination raises the bar significantly above metadata-only schemes.

> **Pragmatic scope.** v1.1 core build: **images only**, with both EXIF manifest and DCT pixel watermark. Video sealing remains a stretch goal — MP4 box writing is fiddly and not worth a day's work if the image path lands first. Engineer C focuses on images with both layers solid, then attempts video.

### Module interface

```kotlin
interface SealEngine {
    suspend fun seal(
        bytes: ByteArray,             // raw image or video bytes
        captureMeta: CaptureMeta,
        detection: Verdict
    ): SealedFile
}

data class SealedFile(
    val bytes: ByteArray,             // bytes with manifest embedded
    val manifest: Manifest,           // for logging / display
    val sigOk: Boolean
)
```

---

## 8. Component: Verification pipeline

**Owner:** Engineer C, with classifier hand-off to Engineer A for unprovenanced content

### Goal

Given any file (image or video), produce a single verdict that the UI can render: `VERIFIED ORIGINAL`, `VERIFIED-BUT-MODIFIED`, `BROKEN SEAL`, `NO PROVENANCE` (with detection result), or `UNREADABLE`.

### Verification logic

```kotlin
fun verify(file: File): VerifyResult {
    val manifestRaw = ExifReader.readPramanaManifest(file)
        ?: return runDetectionFallback(file)         // NO_PROVENANCE
    val manifest = Manifest.parse(manifestRaw)
        ?: return VerifyResult.UNREADABLE
    val publicKey = TrustStore.lookup(manifest.keyId)
        ?: return VerifyResult.BROKEN_SEAL(reason = "unknown_key")
    val canonical = manifest.canonicalize()         // same canon as SealEngine
    val sigOk = CryptoUtil.verify(publicKey, canonical, manifest.signature)
    if (!sigOk) return VerifyResult.BROKEN_SEAL(reason = "signature_invalid")
    val recomputedHash = Sha256.of(file.pixelBytes())
    return if (recomputedHash == manifest.contentHash)
        VerifyResult.VERIFIED_ORIGINAL(manifest)
    else
        VerifyResult.VERIFIED_BUT_MODIFIED(manifest, hashDiff = true)
}
```

### Trust store

The trust store is a small local key registry. The current device's own public key is always present. Future enhancement: bundle a small set of well-known Pramāṇa device keys (e.g. our dev devices) so we can demo cross-device verification on stage. Pre-populate during prep.

### What the UI shows

| VerifyResult | UI rendering |
|---|---|
| `VERIFIED_ORIGINAL` | Green badge, capture timestamp, device hint, all detection metadata from the manifest. |
| `VERIFIED_BUT_MODIFIED` | Amber badge, message: 'Signature valid, but content has been altered since capture.' |
| `BROKEN_SEAL` | Red badge, reason code, and offer: 'Run live detection anyway?' |
| `NO_PROVENANCE` | Neutral badge, runs `DetectionEngine` on the file, shows verdict and confidence. |
| `UNREADABLE` | Grey badge, 'Cannot read provenance metadata. Run detection?' |

---

## 9. Component: Mobile platform and camera

**Owner:** Engineer B — Mobile platform & camera pipeline

### Goal

Build the Android app skeleton, wire the camera through CameraX to feed `DetectionEngine`, manage app lifecycle, handle permissions, and coordinate the verdict bus that fans out results to the overlay renderer and the seal trigger.

### Why CameraX, not Camera2

Camera2 is more powerful but punishing — its lifecycle and threading model is a primary source of LLM hallucinations and runtime crashes. CameraX is the official lifecycle-aware wrapper that handles the worst of Camera2's edge cases. We use CameraX's `ImageAnalysis` use case to tap frames pre-display, which is exactly what the problem statement asks for.

Setup pattern (v1.1 — YUV output with pre-allocated buffers):

```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)  // NOT RGBA_8888
    .setTargetResolution(Size(640, 480))
    .build()

// Allocate buffers ONCE at startup, reuse on every frame.
// No new ByteArray / FloatArray inside the analyzer callback.
private val yuvBuffer = ByteBuffer.allocateDirect(YUV_BYTES)
private val rgbBuffer = ByteBuffer.allocateDirect(RGB_BYTES)
private val modelInput = FloatArray(3 * 224 * 224)

imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
    YuvToRgb.convert(imageProxy, yuvBuffer, rgbBuffer)   // in-place
    PreProc.faceCropAndNormalize(rgbBuffer, modelInput)   // in-place
    val verdict = detectionEngine.analyze(modelInput, imageProxy.width, imageProxy.height)
    verdictBus.emit(verdict)
    imageProxy.close()
}
```

> **Engineering rule.** Zero allocations inside the per-frame analyzer callback. Every `ByteBuffer`, `FloatArray`, and `Bitmap` is allocated once at startup and reused. If you find yourself writing `val foo = ByteArray(...)` or `Bitmap.createBitmap(...)` in the analyzer, stop — that allocation runs 30 times per second, triggers GC, and costs you frames. Pre-allocate, reuse, profile.

### Frame rate management

30 fps inference is the target. The strategy: target 30 fps preview, run inference on every available frame, accept that the analyzer may skip frames if the model is slow (`STRATEGY_KEEP_ONLY_LATEST` gives us this for free — the analyzer always works on the most recent frame and drops older ones). The verdict overlay updates at whatever rate the analyzer produces — typically 25 to 30 fps in practice.

### Capture flow

When the user taps the shutter button, the app captures a high-resolution still through CameraX's `ImageCapture` use case. The current verdict from the verdict bus (taken at the moment of shutter) is bundled with the capture metadata and passed to `SealEngine`. `SealEngine` returns sealed bytes; the app writes them to `MediaStore`. For video, replace `ImageCapture` with `VideoCapture` and seal on `stop()`.

### Permissions and lifecycle

Required permissions: `CAMERA`, `READ_MEDIA_IMAGES` (Android 13+), `READ_MEDIA_VIDEO`. Permission grant happens at first launch with a brief explanation screen. Camera lifecycle is bound to the activity lifecycle through CameraX's `ProcessCameraProvider.bindToLifecycle(this, selector, useCases)`. Engineer B must test backgrounding, foregrounding, rotation, and split-screen — these are the four most common crash sources.

### Module interface

```kotlin
interface CameraEngine {
    fun start(lifecycleOwner: LifecycleOwner, surface: SurfaceProvider)
    fun stop()
    fun captureStill(onResult: (SealedFile) -> Unit)
    val verdictBus: SharedFlow<Verdict>
}
```

---

## 10. Complete tech stack

| Layer | Tool / library | Why |
|---|---|---|
| Language | Kotlin (Android), Python (training, Workbench scripts) | Standard, well-supported, vibe-friendly. |
| Mobile framework | Android, minSdk 28, targetSdk 34 | Wide device support, modern API surface. |
| UI | Jetpack Compose | Modern, declarative, less boilerplate. Final polish at end. |
| Camera | `androidx.camera` (CameraX) | Lifecycle-aware Camera2 wrapper. Far less crash-prone. |
| Face detection | MediaPipe Face Detection (qai-hub-models) | Already NPU-optimized. Drop-in face crop. |
| Deepfake classifier | MobileNet-V3-Small or EfficientNet-B0 backbone, custom binary head | Both in qai-hub-models with confirmed HTP support. |
| Model training | PyTorch + timm | Standard ML stack. Vibe-friendly. |
| Quantization & deployment | Qualcomm AI Hub Workbench (`qai_hub_models` pip package) | Compile, quantize INT8 and INT4, profile on cloud Snapdragon devices, all from Python. |
| On-device runtime | TFLite + QNN HTP Delegate | Easier than raw QNN SDK. Same Hexagon acceleration. |
| Runtime fallback | TFLite + GPU Delegate, then XNNPACK CPU | If HTP fails, the app still runs. |
| Crypto | Android Keystore, ECDSA-P256 (primary) or Ed25519 (if API 33+) | Hardware-backed via StrongBox / TEE. |
| Canonicalization | RFC 8785 JCS — published library (Kotlin/Java + Python) | Eliminates JSON key-order and float-formatting drift between Sealer and Verifier. |
| Hashing | `java.security.MessageDigest` (SHA-256) | Standard library. Zero risk. |
| EXIF I/O | `androidx.exifinterface` | Official Android EXIF library. |
| DCT pixel watermark | Custom 8×8 block DCT in Kotlin using JTransforms or Apache Commons Math | Two-tier watermark — survives EXIF stripping by consumer messaging apps. |
| rPPG signal processing | JTransforms (FFT), MediaPipe Face Mesh, Kotlin math | POS algorithm + bandpass FFT. All CPU, runs parallel to NPU. |
| MP4 metadata (stretch) | `mp4parser` or hand-rolled udta box writer | For video sealing — stretch goal. |
| JSON serialization | `kotlinx.serialization` (data classes) + JCS for canonicalization | Type-safe Kotlin-native JSON; JCS handles the canonical form for signing. |
| Datasets | FaceForensics++ (c23), Celeb-DF-v2, IIIT-CFW | Standard deepfake benchmarks plus Indian-face augmentation. |
| Profiling | Snapdragon Profiler, AI Hub Workbench profile reports | Live and pre-recorded latency numbers for the pitch. |
| Source control | GitHub, single repo, branch per engineer | Standard. Engineer B owns `main`. |

---

## 11. Redundancy and failover

For every component that could fail, we have a pre-planned fallback. The principle: **no single failure should make the demo unshowable.** Each failure has been thought through and the fallback is baked into the code, not improvised on stage.

| Failure scenario | Default behavior | Demo impact |
|---|---|---|
| QNN HTP delegate fails to load | Auto-fall back to GPU delegate, then CPU. Backend label in HUD updates. | Pitch language changes from 'running on Hexagon NPU' to 'on the available accelerator.' Lose the strongest Qualcomm slide, keep everything else. |
| INT4 model unusable on issued chip | Demo INT8 only; mark INT4 as 'attempted and characterized; full deployment in next iteration.' | Lose one slide bullet. Story unchanged. |
| Detection model accuracy poor on live judge sample | Confidence threshold renders 'UNCERTAIN' rather than confident-wrong. UI does not promise certainty. | Show curated demo gallery as primary; live judge samples as 'best-effort.' |
| StrongBox unavailable on issued device | Fall back to TEE-backed Keystore. Pitch language: 'hardware-backed asymmetric signature.' | Drop the StrongBox-specific bullet. Story unchanged. |
| EXIF stripped by messaging app (WhatsApp/Signal/Instagram) | DCT pixel watermark survives stripping. Verifier reads the watermark fingerprint and looks up the manifest from trust store or sidecar. | Demo flow: send a signed photo through WhatsApp, receive on second phone, watermark detected, manifest fetched, file still verifies. |
| rPPG unavailable (still photo, no face, low landmark confidence) | Fusion falls back to NPU-only score. UI shows 'biological liveness: not applicable'. | Graceful — verdict still produced, just from one stream. |
| rPPG noisy under venue fluorescent lighting | Hard-veto threshold relaxed via demo-mode toggle. Pre-recorded reference clip available for Scene 2 if live is too noisy. | Best-case: live demo. Worst-case: pre-recorded fallback. Story intact. |
| Ed25519 unsupported in Keystore on issued device | Fall back to ECDSA-P256 — supported universally. | Zero impact. Both are hardware-backed asymmetric signatures. |
| EXIF library cannot embed UserComment on a particular format | Companion sidecar file (`.pmna`) containing the same manifest. | Slight UX hit; the seal still verifies. |
| CameraX crash on device | App opens in gallery-only mode; live capture disabled. | Lose Scene 1 of the demo. Verify mode demo still works. |
| App freezes mid-demo | Backup demo video plays from laptop. Narrate over. | Zero loss if executed calmly. Judges have seen this before. |
| Live deepfake video does not detect cleanly | Show curated samples first to establish credibility, then mention the limitation honestly. | Honesty wins more points than fake-confidence loses. |

### The backup video

On the night before the hackathon finale, Engineer A or B records a 3-minute screen capture of the full demo running cleanly on the dev device. This file lives on every team member's laptop. If the live demo goes wrong on stage, switch to the backup video at the point of failure and narrate over it. **Practice this transition.** The transition should look intentional, not panicked.

---

## 12. Team distribution: three engineers, parallel tracks

Three engineers, three independent tracks. Each track ships a module that conforms to an interface. The app integrates the modules at the end. All three can work in parallel for the full 20 days of prep without blocking each other, because each track has a stable mock to develop against.

**UI and polish are done collectively at the end of prep and on hackathon day.** No single engineer is assigned to 'frontend' — instead, each engineer ships a tiny debug surface for their own module during development (a screen that shows raw outputs for testing), and the team polishes the final user-facing UI together as the last task.

### Engineer A — Detection & Model

The ML engineer. Owns everything model-related from training data to deployable `.tflite` file.

**Owns**

- Dataset preparation, splits, and Indian-face augmentation.
- Model training in PyTorch (MobileNet-V3-Small and EfficientNet-B0 backbones, binary classification heads).
- Conversion through AI Hub Workbench: compile, quantize INT8 and INT4, profile on cloud Snapdragon.
- Final detection deliverable: two `.tflite` files (INT8 and INT4) plus a Python reference inference script that defines the exact preprocessing and postprocessing contract.
- `Verdict` object schema (what comes out of `analyze()`).
- Grad-CAM heatmap generation in the model export.
- **(v1.1) rPPG cardiac liveness pipeline.** MediaPipe Face Mesh integration, ROI patch extraction, POS algorithm, FFT bandpass, SNR scoring. All CPU, all Kotlin (or Python prototype → Kotlin port). Delivers an `rPPGStream` module that runs in parallel with detection and emits liveness scores.
- **(v1.1) Fusion layer logic.** Combines NPU classifier score and rPPG score into the final verdict, including the hard-veto rule and graceful fallback when rPPG is unavailable.

**Pre-hackathon deliverables (the 20-day window)**

- Trained PyTorch checkpoint with AUC > 0.85 on FF++.
- INT8 `.tflite` verified on cloud Snapdragon via Workbench, latency profiled and screenshotted.
- INT4 `.tflite` attempted; if quantization works, ship both — if not, document why.
- Python reference script that produces exact pre/post contract.
- Curated demo gallery: 8 to 12 deepfake samples we know the model handles cleanly.
- **(v1.1) rPPG module ported to Kotlin**, runs end-to-end on a regular Android phone, validated on a webcam recording of a human face (positive case) and a recording of a phone screen showing a face (negative case — should show no pulse).
- **(v1.1) Fusion layer integrated**, with unit tests covering all combinations of rPPG-available × score-thresholds.
- Two-page model card with dataset, training config, accuracy numbers, latency numbers.

**Hackathon-day responsibilities**

- Be the human bridge if Engineer B's integration produces unexpected detection outputs — likely a preprocessing mismatch.
- Monitor on-device latency once integrated; recommend GPU or CPU fallback if HTP underperforms.
- Generate Grad-CAM heatmaps on tap during the demo.

### Engineer B — Mobile platform & camera

The Android engineer. Owns the app skeleton, camera pipeline, model integration, and inter-module wiring.

**Owns**

- Android Studio project setup, Gradle dependencies, minSdk/targetSdk configuration.
- CameraX integration with `ImageAnalysis` (`YUV_420_888` output) and `ImageCapture` use cases.
- Permissions UX (first-launch flow).
- On-device model runner: TFLite + QNN HTP Delegate, with GPU and CPU fallback ladder.
- Frame-to-tensor preprocessing matching Engineer A's Python reference exactly. **(v1.1) Pre-allocated `ByteBuffer`s, zero per-frame allocations.**
- Verdict bus (Kotlin `SharedFlow`) — fans verdicts out to UI and capture pipeline.
- Lifecycle, threading, memory management — the parts where LLMs hallucinate hardest.
- **(v1.1) UI integrator from day 17 onward.** Owns the Jetpack Compose codebase: merges incoming UI from A and C, enforces the theme, catches state-hoisting and recomposition bugs, owns the verdict bus → overlay rendering binding. Designs are collective; the integration is B's.

**Pre-hackathon deliverables**

- Working Android app on a regular phone (no Snapdragon required) running a mock `DetectionEngine`.
- When Engineer A delivers the `.tflite`, swap mock for real and confirm end-to-end inference.
- Verdict bus flowing, basic overlay rendering on camera preview (just the text label is enough).
- `ImageCapture` working; capture path stubbed to call `SealEngine` with a stub implementation.
- Backend fallback ladder coded and tested by forcing HTP unavailable.
- **(v1.1) Pre-allocated buffer pattern in place from day one**; performance smoke test confirms no GC pauses during the analyzer loop.
- **(v1.1) Borrow a Snapdragon-equipped phone (Galaxy S22/S23/S24, Xiaomi 13/15, RB3 Gen 2 dev kit) from someone on campus for at least one prep session.** Install the latest APK, validate QNN delegate loading and HTP execution. Even a few hours of real-device testing during prep dramatically reduces hackathon-day risk. If no device is available on campus, document the QNN initialization path you'll attempt first on hackathon day.

**Hackathon-day responsibilities**

- First task on the issued device: install the app, confirm camera permission flow, run a smoke test.
- Integrate the real `.tflite`, switch backend to HTP, validate end-to-end.
- Profile and tune frame skip / resolution if needed to hit 30 fps.
- Wire `SealEngine` into the capture path once Engineer C confirms it works on the device.

### Engineer C — Provenance & cryptography

The security engineer. Owns sealing, verification, manifest schema, and the trust model.

**Owns**

- Keystore key generation with StrongBox flag and fallback ladder (StrongBox → TEE → RSA).
- Manifest schema design and serialization.
- **(v1.1) Canonicalization pinned to RFC 8785 JCS** — use a published library, do not hand-roll. All numeric metadata stringified.
- SHA-256 hashing of pixel bytes and metadata blob.
- ECDSA-P256 (or Ed25519 if supported) signing through Keystore.
- EXIF UserComment embedding for images.
- **(v1.1) DCT pixel watermark** — second authenticity layer. 8×8 block DCT in luminance channel, mid-frequency coefficient modification, ~256-bit payload with Reed-Solomon redundancy. Embedded on save, scanned on verify.
- Verification logic: parse manifest, look up key, verify signature, recompute hash, classify result. **(v1.1) Also: scan DCT watermark when EXIF manifest absent.**
- Trust store: local registry of known public keys.
- Stretch: MP4 udta box writing for video, full C2PA JUMBF container, cross-device key federation.

**Pre-hackathon deliverables**

- `SealEngine` library that signs a byte array and embeds the manifest in EXIF. Tested on a regular Android phone — Keystore signing works on every modern Android device, no Snapdragon required.
- **(v1.1) DCT watermark embedding + extraction working end-to-end on test images**, including against JPEG quality-70 recompression to verify robustness.
- **(v1.1) RFC 8785 JCS canonicalization library integrated**, three signed test vectors checked into the repo with their expected canonical bytes and signatures.
- `VerifyEngine` library that round-trips: any file Pramāṇa sealed should verify; any modification should be caught; **(v1.1) any file with stripped EXIF should still be detected via the DCT watermark.**
- Canonical JSON serializer agreed with the `SealEngine`/`VerifyEngine` pair (now via JCS library, not hand-rolled).
- CLI test harness (a small Kotlin or Python tool) that runs the seal/verify roundtrip on sample images, including a stripped-EXIF case.
- Documentation of the manifest schema in this bible, kept current as the schema evolves.

**Hackathon-day responsibilities**

- On the issued device, confirm StrongBox availability and validate the chosen key algorithm works in hardware.
- If StrongBox is unavailable, fall back to TEE and update the pitch language.
- Wire `SealEngine` into Engineer B's capture pipeline.
- Pre-populate the trust store with the dev devices' public keys for cross-device verification demo.

### Parallel timeline

All three engineers can start day one of the 20-day prep. None of them is blocked by any other. Engineer B uses a mock `DetectionEngine` until Engineer A delivers, and a mock `SealEngine` until Engineer C delivers — both mocks are trivial (return canned data). The integration phase is the last 3 days of prep, when real implementations replace mocks.

| Phase | Engineer A | Engineer B | Engineer C |
|---|---|---|---|
| Days 1–3 | Dataset prep, training pipeline setup | Android skeleton, CameraX (YUV), mock `DetectionEngine`, buffer reuse pattern | Keystore design, manifest schema, RFC 8785 JCS library integration |
| Days 4–10 | Train backbones, validate accuracy. Prototype rPPG in Python on webcam recordings. | Camera preview + analyzer + verdict bus + overlay text. Borrow Snapdragon phone, install APK, validate QNN path. | `SealEngine` + `VerifyEngine` (EXIF layer), CLI tests, JCS test vectors checked in |
| Days 11–15 | AI Hub Workbench: compile, INT8/INT4 quantize, profile. Port rPPG to Kotlin module. | Backend fallback ladder, capture path with stub seal | DCT watermark embed/extract, round-trip tests including stripped-EXIF case. Trust store. |
| Days 16–17 | Fusion layer integrated, unit tests. Curate demo gallery, finalize model card | Integrate real model and rPPG module, end-to-end smoke test | Integrate DCT watermark into Sealer; integrate Verifier into app gallery flow |
| Days 18–20 | Backup videos, pitch dry runs | UI integrator hat on: merge UI from A and C, theme polish, performance pass | Polish, polish, polish. Cross-device key federation prep. |
| Hackathon Day 0–8 hr | Bridge integration, monitor inference, tune rPPG for venue lighting | Deploy on Snapdragon device, HTP wire-up, frame-rate tuning. UI integrator role active. | Validate Keystore on device, wire seal pipeline, confirm DCT survives device-recompression |
| Hackathon Day 8–16 hr | Curate / re-curate live samples | UI polish — B is the merger, A and C contribute screens | Polish, polish, polish |
| Hackathon Day 16–24 hr | Pitch rehearsal | Pitch rehearsal | Pitch rehearsal |

---

## 13. Vibe-code vs manual matrix

Opus 4.7 is your fastest engineer. It is also occasionally a hallucinating engineer, particularly when it comes to platform-specific APIs with version-sensitive surfaces. This section tells you, for every task in the project, whether to vibe-code freely, vibe-code carefully with verification, or do the work manually.

### VIBE-safe: let Opus drive

These tasks are well-represented in training data, have stable APIs, and produce verifiable outputs. Let Opus generate freely; spot-check the output.

- PyTorch training scripts. Standard ML boilerplate.
- Dataset preprocessing. Image resizing, normalization, augmentation.
- ONNX export from PyTorch. Standard pattern.
- JSON schema serializers in Kotlin (`kotlinx.serialization`). Well-documented.
- SHA-256 hashing in Kotlin or Python. Standard library.
- Kotlin data classes, sealed classes, enums. Pure Kotlin, no platform surface.
- Unit tests for `VerifyEngine` round-trips. Pure logic, easy to verify.
- Mock implementations of any interface. No platform dependencies.
- **(v1.1) DCT watermark math.** Classical 8×8 block DCT is textbook signal processing. Opus can produce the embed/extract code competently; verify against a reference implementation by round-tripping known payloads through JPEG-recompression.
- **(v1.1) POS algorithm and FFT for rPPG.** Well-documented in the published paper (Wang et al. 2017). Opus can produce the Python reference. Port to Kotlin manually using JTransforms for the FFT.
- Documentation, README, slide content. Opus writes English well.

### VIBE-with-verification: let Opus draft, you verify against docs

These tasks Opus can draft cleanly but the output should be cross-checked against current official documentation, because the APIs change version-to-version and Opus may produce a slightly-stale variant.

- **CameraX use case setup** (`ImageAnalysis`, `ImageCapture`). Verify against the current `androidx.camera` reference. **(v1.1) Specifically validate the `YUV_420_888` output path and `ByteBuffer` reuse pattern — Opus tends to default to `RGBA_8888` examples.**
- Jetpack Compose UI scaffolding. Verify against the current Compose docs.
- Gradle dependency versions. Opus often picks an older version. Cross-reference Maven Central.
- EXIF I/O via `androidx.exifinterface`. Verify tag IDs and field types.
- Permission handling on Android 13+ (granular media permissions). Recent API changes; verify on the official Android dev page.
- **(v1.1) MediaPipe Face Mesh integration on Android.** The Kotlin Tasks API surface is recent; verify against the current MediaPipe Android docs.
- **(v1.1) RFC 8785 JCS library selection.** Several implementations exist; pick one with active maintenance and confirmed test-vector compliance. Don't accept an Opus-suggested library without checking its repo activity.

### MANUAL: do it yourself, with care

These tasks are where Opus hallucinates hardest. They involve narrow Qualcomm-specific or hardware-specific surfaces with non-obvious correct usage. Do the work yourself with the official docs open, and have Opus review your code after rather than write it.

- **Qualcomm AI Hub Workbench:** model upload, compile target selection, quantize job configuration. Done through the Workbench web UI and the `qai_hub_models` CLI. Opus has limited knowledge of the exact incantations because the API surface is recent and version-sensitive.
- **QNN TFLite Delegate initialization in Android.** The exact way to load the delegate, set its options, and bind it to the TFLite interpreter is non-obvious and changes per version. Use the official Qualcomm sample code in `ai-hub-apps` as your reference.
- **Android Keystore key generation with StrongBox flag.** The `KeyGenParameterSpec.Builder` method names and order matter. The fallback handling (catching `StrongBoxUnavailableException`) is fiddly. Reference: the Android Security samples on developer.android.com.
- **The canonical JSON serializer used in signing.** Both Sealer and Verifier must produce byte-for-byte identical canonical bytes. Implement once, manually, with test vectors. If they ever diverge, every signature breaks.
- **Frame-to-tensor preprocessing.** Pixel layout, normalization range, channel order. Tiny mistakes here are silent and cause the model to underperform. Implement to match the Python reference exactly, with a unit test that compares numpy and Kotlin outputs on the same image bytes.
- **JNI bridges** (if any). Only needed if the QNN TFLite Delegate doesn't cover something. Avoid unless necessary.
- **Demo flow rehearsal.** No AI helps you here. Practice the demo twice on hackathon day.

### Anti-patterns

- **Don't ask Opus to write your Gradle file.** It will produce an outdated configuration. Use Android Studio's new-project wizard, then ask Opus to add specific dependencies one at a time.
- **Don't ask Opus to design your manifest schema.** It is fine for Opus to format JSON, but the schema is the contract between Sealer and Verifier. Decide it manually, write it down, never let it drift.
- **(v1.1) Don't hand-roll JSON canonicalization.** Use the RFC 8785 JCS library. Hand-rolled canonicalizers drift on floating-point and key-order edge cases, and the drift is silent — every signature breaks and you don't find out until it's too late. Stringify all numeric metadata as a belt-and-braces defense.
- **Don't vibe-code the Keystore key generation.** Get the exact parameter spec from the Android docs. Subtle mistakes here mean signatures don't verify and you don't find out until the demo.
- **Don't trust Opus's claims about QNN performance numbers.** The only valid latency numbers are from AI Hub Workbench profiling on real hardware. Never let made-up numbers into the pitch deck.
- **(v1.1) Don't allocate inside the analyzer callback.** Every `ByteArray(...)`, `FloatArray(...)`, or `Bitmap.create...()` in the per-frame path runs 30 times per second. Pre-allocate, reuse, profile.

---

## 14. Risk register

Top risks ranked by impact × likelihood. Each has a pre-decided mitigation. The mitigation is what you **do** — not what you wish would happen.

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| QNN HTP delegate fails on hackathon device | High | Medium | GPU and CPU fallbacks pre-coded. App runs, story shifts slightly, demo intact. |
| Model accuracy embarrasses on unfamiliar fakes | High | Medium | Curated demo gallery as primary. Confidence threshold prevents confident-wrong. Honest framing in pitch. |
| StrongBox unavailable on issued device | Medium | Medium | TEE fallback. Pitch language adjusts. No demo impact. |
| AI Hub Workbench quota or auth issue during prep | High | Low | Have backups: torch-quantized ONNX models work via plain TFLite. Lose the 'profiled on cloud Snapdragon' slide, keep the rest. |
| EXIF embedding incompatible with capture format | Medium | Low | Companion `.pmna` sidecar file fallback. |
| CameraX behaves unexpectedly on issued device | High | Low | Reduce resolution and frame rate. Switch to gallery-only mode if catastrophic. |
| Live demo crashes on stage | Catastrophic | Low | Backup video plays, narrate over. Practiced transition. |
| Team member sick / unavailable hackathon day | High | Low | Module independence. Each engineer's track ships independently. UI polish is collective and absorbable. |
| Wi-Fi or laptop tooling fails at venue | Medium | Medium | Pre-load everything onto local drives. Have an offline copy of the demo gallery, the model files, and the backup video. |
| Judge asks a question you cannot answer | Low | High | Default response: 'good question, here's what we know and here's what we'd test next.' Honesty over bluffing. |

---

## 15. Stretch goals

Priority-ordered. Attempt in order. Only attempt N+1 after N is solid. **Core build comes first. Stretches that break the core get abandoned.**

*(rPPG cardiac liveness and DCT pixel watermarking moved to core in v1.1 — they are no longer stretches. The remaining list is renumbered.)*

**Stretch 1 — Grad-CAM heatmap overlay.** Render the attention heatmap on the camera preview when the user taps a face. Engineer A produces the heatmap data; Engineer B's overlay renderer composites it. Half-day of work. Visual impact in the demo is significant — judges see why the model said fake.

**Stretch 2 — INT4 deployment polish.** If INT4 quantization works at all (Workbench may or may not produce a usable INT4 variant of the chosen backbone), benchmark it cleanly and show side-by-side INT4 vs INT8 numbers in the pitch. Demonstrates that the team knew the problem statement said 'INT4/INT8' and shipped both.

**Stretch 3 — Live NPU telemetry HUD.** On-screen overlay showing live FPS, inference latency ms, backend label (NPU/GPU/CPU), and battery / power if accessible. Pulled from the TFLite delegate introspection API and Android's `BatteryManager`. Engineer B's task. Pure UI overlay — no risk to the core.

**Stretch 4 — Video sealing via MP4 udta box.** Extend `SealEngine` to handle MP4 metadata embedding. Engineer C's task. Adds video to the demo. Estimated effort: a full day. Skip if the image path is not bulletproof first.

**Stretch 5 — Full C2PA JUMBF compliance.** Replace the EXIF-embedded JSON with a full C2PA JUMBF container via the `c2pa-rs` Rust crate behind JNI. If it works, sealed files verify on Adobe's public `verify.contentauthenticity.org`. This is the one-slide knockout: a Pramāṇa file uploaded to the public verifier shows green. Estimated effort: 2 to 3 days. Only attempt if everything else is done.

**Stretch 6 — Cross-device verification demo.** Pre-populate the trust store with the dev devices' public keys. Take a photo on Phone A, send the file to Phone B (Bluetooth, AirDrop equivalent, or just USB), verify on Phone B. The verification shows green because Phone B knows Phone A's public key. Half-day of work and a strong demo moment.

**Stretch 7 — Diffusion-fake-aware detection head.** Add a second classification head trained on diffusion-generated samples (DiffusionForensics or curated synthetic set) running alongside the GAN-era head. Two heads vote; either flagging FAKE pushes the verdict. Modest robustness gain against modern generators. Engineer A's task. Effort: 1 to 1.5 days. Only attempt if the primary head is solid.

**Stretch 8 — Audio deepfake detection.** Use YamNet (in `qai-hub-models`) to detect synthetic voice artifacts in the audio track of captured videos. Engineer A's task. Adds a new signal pipeline. Effort: 1.5 to 2 days. Real risk: another integration point. Only if drastically ahead.

---

## 16. Honest limitations

Things we are not claiming. If a judge asks any of these, the honest answer wins more points than a bluff.

- **We have not solved deepfake detection.** Our detector is good on the datasets we trained on. Against novel adversarial fakes from tomorrow's diffusion models, accuracy will drop. The provenance half of the system is the long-term answer; detection is the short-term defense.
- **Our manifest is not full C2PA.** It is C2PA-shaped, uses the same cryptographic primitives, and conveys the same information. But it lives in EXIF plus a DCT pixel watermark, not JUMBF, and does not verify on the public C2PA verifier. Upgrading to JUMBF is stretch goal 5.
- **Our Indian-face dataset is small.** Around 500 real samples augmenting FF++. Meaningful but not exhaustive. We characterize this as 'ongoing calibration,' not 'solved bias.'
- **Verification scope is local.** Files sealed by Pramāṇa verify in Pramāṇa, or in any other app that knows the public key. There is no global Pramāṇa trust authority. Building one is a future product question, not a hackathon question.
- **Screen-capture attacks are only partially defended.** A deepfake video played back on a screen and recorded by Pramāṇa fails the rPPG cardiac liveness check (no biological pulse in the captured pixels) — this is real defense, not theatrical. But a static deepfake image displayed on a screen and photographed would still pass, because rPPG needs video. Photographing-a-screen-of-a-photo attacks remain an open vector. Addressing them requires depth or moiré detection — future work.
- **rPPG has real limits.** It requires a face video (not stills) with visible skin, decent lighting, and stable framing. It is weaker on very dark skin tones under poor lighting. We acknowledge this in the UI by showing 'biological liveness: not applicable' rather than fake-confidence when conditions are wrong.
- **The DCT watermark survives recompression at the same resolution, NOT resizing.** Empirically (CI-tested): with redundant-block embedding + majority vote it recovers through JPEG re-encoding down to ~q60 at the original resolution — covering EXIF stripping, re-saving, and screenshots at native resolution. A **downscale/resize** (e.g. WhatsApp shrinking a >1600px image, or a crop) changes the 8×8 block grid and destroys it. For those flows the EXIF manifest is the primary channel; the watermark is defense-in-depth against pure metadata stripping. We do not claim resize-survival. Heavy filtering or deliberate frequency-domain attacks also destroy it. Removing both layers leaves forensic traces.
- **Audio is not in scope unless stretch 8 lands.** A real face dubbed with cloned audio would currently pass our visual detector. Honest about it.
- **Our cryptographic stamp does not prove the photo is 'true.'** It proves the photo came from a Pramāṇa-equipped camera at a specific moment with specific metadata, that no edits have been made since capture, and (via rPPG) that for video the subject had a real pulse. Whether the scene depicted is itself real (as opposed to staged) is outside what cryptography can answer.

---

## 17. Appendix A: Interface contracts and code templates

### Verdict data model

```kotlin
enum class VerdictLabel { GENUINE, SUSPICIOUS, FAKE }

data class FrameInput(
    val rgb: ByteArray,          // RGB packed bytes
    val width: Int,
    val height: Int,
    val timestampNs: Long
)

data class Verdict(
    val label: VerdictLabel,
    val confidence: Float,       // 0.0 .. 1.0; for FAKE, higher = more confident fake
    val heatmap: FloatArray?,    // 7x7 or 14x14 flattened, normalized 0..1
    val latencyMs: Int,
    val backend: String,         // 'NPU' | 'GPU' | 'CPU'
    val modelId: String          // 'pramana-mobilenet-v3-small-int8-v1'
)
```

### Manifest data model

```kotlin
@Serializable
data class Manifest(
    val version: String = "1.0",
    val generator: String = "Pramana/1.0",
    val capturedAt: Long,
    val deviceFingerprint: String,
    val sensor: SensorMeta,
    val contentHash: String,      // hex SHA-256
    val detection: DetectionMeta,
    val keyId: String,            // hex SHA-256 of public key
    val signature: String         // base64
) {
    fun canonicalize(): ByteArray {
        // RFC 8785 JCS over all fields except 'signature'.
        // Both Sealer and Verifier MUST call this identically.
        return Jcs.encode(this.copy(signature = ""))
    }
}

@Serializable
data class SensorMeta(
    val model: String,
    val iso: String,             // stringified per v1.1
    val exposureUs: String,
    val focalLengthMm: String
)

@Serializable
data class DetectionMeta(
    val score: String,           // stringified per v1.1
    val label: String,
    val model: String,
    val backend: String
)
```

### Mock implementations (use for parallel development)

```kotlin
// Engineer B drops this in until Engineer A delivers a real model
class MockDetectionEngine : DetectionEngine {
    private var counter = 0
    override suspend fun analyze(frame: FrameInput): Verdict {
        counter++
        val label = when (counter % 30) {
            in 0..20 -> VerdictLabel.GENUINE
            in 21..27 -> VerdictLabel.SUSPICIOUS
            else -> VerdictLabel.FAKE
        }
        return Verdict(label, 0.85f, null, 5, "MOCK", "mock-v1")
    }
    override fun isReady() = true
    override fun backend() = "MOCK"
}
```

### Test vectors for canonical JSON

Engineer C produces 3 to 5 test vectors during prep: a fixed Manifest with known bytes, the canonicalized output, and the expected signature (computed with a fixed test key). These are checked into the repo at `tools/test_vectors/`. Both `SealEngine` and `VerifyEngine` must reproduce the test vectors exactly. If they drift, fix the canonicalizer.

---

## 18. Appendix B: Glossary

| Term | Meaning |
|---|---|
| AIMET | AI Model Efficiency Toolkit — Qualcomm's PyTorch / TF quantization library. Mostly subsumed by AI Hub Workbench for our use case. |
| AI Hub Workbench | Qualcomm's cloud service for compiling, quantizing, profiling, and running models on cloud-hosted Snapdragon devices. We use it heavily. |
| C2PA | Coalition for Content Provenance and Authenticity — the industry standard manifest format for media provenance, used by Adobe, BBC, Microsoft, and others. |
| DCT | Discrete Cosine Transform — basis of JPEG compression; used in invisible watermarking schemes. |
| Ed25519 | An elliptic-curve signature scheme (EdDSA over Curve25519). Fast, small signatures. Supported in Android Keystore from API 33. |
| ECDSA | Elliptic Curve Digital Signature Algorithm. Our default; supported universally in Android Keystore. |
| EXIF | Exchangeable Image File Format — metadata standard for image files. We embed our manifest in the `UserComment` field. |
| FaceForensics++ (FF++) | Standard deepfake detection dataset, ~1000 source videos with manipulated variants. We train primarily on its c23-compressed split. |
| Grad-CAM | Gradient-weighted Class Activation Mapping. Produces a coarse heatmap showing which image regions drove a classifier's decision. |
| Hexagon HTP | Hexagon Tensor Processor — the NPU portion of Snapdragon SoCs. The target accelerator for our inference. |
| JCS | JSON Canonicalization Scheme (RFC 8785). Deterministic JSON serialization for signing. |
| JUMBF | JPEG Universal Metadata Box Format — the binary container that C2PA uses to embed its manifest in media files. |
| Keystore (Android) | Android's secure key storage system. Backed by StrongBox or TEE on Snapdragon devices. |
| NPU | Neural Processing Unit — generic term for AI accelerators. On Snapdragon, the NPU = Hexagon HTP. |
| QNN | Qualcomm Neural Network — Qualcomm's runtime and SDK for executing models on their hardware. Our deployment path uses the QNN TFLite Delegate. |
| QNN TFLite Delegate | The TFLite plugin that hands inference off to QNN at runtime. Easier to integrate than raw QNN SDK with `.dlc` files. |
| rPPG | Remote Photoplethysmography — extracting pulse signal from subtle skin color changes captured on camera. Core feature in v1.1. |
| StrongBox | Hardware security module integrated into modern Snapdragon SoCs. Keys generated with this flag never leave the secure element. |
| TEE | Trusted Execution Environment — the more general hardware-isolated execution context on ARM SoCs. Less secure than StrongBox but still hardware-backed. |
| TFLite | TensorFlow Lite — Google's mobile inference runtime. Our app uses it with the QNN delegate. |
| UDTA | User Data Atom — the MP4 metadata container where we'd embed video manifests (stretch). |

---

*End of bible. Treat this document as living. If a decision changes during prep, update the bible in the same commit. Every Team Snapped member should be able to open this file and answer any question about what Pramāṇa is and how it works.*

**Team Snapped — Pramāṇa — Hack4SoC 3.0**
