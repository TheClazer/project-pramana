# CLAUDE.md — Pramāṇa project context

> **Read this first** at the start of any session. It is the cliff-notes version of `docs/bible.md`.

---

## What this project is

Pramāṇa is an **Android app** for the Hack4SoC 3.0 hackathon (IEEE RVCE × Qualcomm) that does **on-device deepfake detection + cryptographic provenance sealing** on Snapdragon hardware. No cloud, no backend, no telemetry.

The full spec is in [`docs/bible.md`](docs/bible.md). **The bible wins** if anything you remember from chat contradicts it. If a decision changes, update the bible in the same commit.

---

## Who the user is

- **Rayyan** — building this for Hack4SoC 3.0.
- Stated as "team of 3" but **building solo** ("All three" engineers).
- **Timeline: ~10 days** until the hackathon starts (as of session start). Wanted **no scope cuts**.
- **Hardware in hand:** iQOO Z5 (Snapdragon 778G) — has Hexagon NPU, TEE Keystore. Good enough for dev; not the flagship the bible targets.
- **Windows 11**, PowerShell shell, project at `D:\Work\project-pramana`.
- User said "GO BUILD" twice — the autonomous build below is mostly done; the remaining items are gated on user-only actions (datasets, Workbench token, QNN AAR, device).

---

## How to work on this codebase

### The bible's three rules (always apply)
1. **Default to the bible.** When in doubt, what `docs/bible.md` says wins.
2. **Each engineer owns their section.** Even though one human is doing all of it, keep the module boundaries clean — they exist so failures don't cascade.
3. **Independence is non-negotiable.** No module should tightly couple to another. Every module has a mock; the app must run with any subset of real impls.

### Vibe-code vs Manual (Section 13 of bible)
The bible explicitly says where Claude is safe vs dangerous. Re-read Section 13 before coding any of these:
- ❌ **MANUAL (do NOT vibe-code freely):** QNN TFLite Delegate init, Android Keystore `KeyGenParameterSpec`, AI Hub Workbench CLI, the JSON canonicalizer, frame-to-tensor preprocessing, JNI bridges.
- ⚠️ **VIBE-with-verification:** CameraX (esp. YUV path), Compose, Gradle versions, EXIF I/O, Android 13+ permissions, MediaPipe Tasks API, JCS library selection.
- ✅ **VIBE-safe:** PyTorch training, dataset prep, ONNX export, Kotlin data classes, SHA-256, mocks, DCT math, POS algorithm, docs.

### Key engineering rules from v1.1
- Camera output: `YUV_420_888`, **never** `RGBA_8888`.
- **Zero allocations** inside the per-frame analyzer callback. Pre-allocate all `ByteBuffer`s / `FloatArray`s at startup, reuse them.
- JSON canonicalization: **RFC 8785 JCS**, use a published library (`io.github.erdtman:java-json-canonicalization`), do NOT hand-roll. All numeric manifest fields stringified.
- Crypto default: **ECDSA-P256** (universal), Ed25519 only if API 33+ and the device supports it.
- Keystore: try `setIsStrongBoxBacked(true)` first, catch `StrongBoxUnavailableException`, fall back to TEE-backed (no flag), fall back to RSA last.

---

## Repo layout

```
docs/              bible.md (source of truth), setup-checklist.md
android/           Kotlin app — version catalog + every module + tests
ml/                Python training + AI Hub Workbench scripts + Colab notebook
tools/
├── cli_sealverify/  Python CLI mirror of Sealer/Verifier (round-trip on Windows)
└── test_vectors/    JCS canonical bytes pinning Android <-> Python contract
.github/workflows/  Android assemble + tests CI, Python CLI + ml CI
HANDOFF.md         What the user must do, in order
README.md          Top-level overview
```

---

## Module ownership

| Module | Path | Engineer | Interface |
|---|---|---|---|
| Camera + lifecycle | `android/.../camera/` | B | `CameraEngine` |
| Detection (TFLite + QNN) | `android/.../detection/` | A | `DetectionEngine` |
| rPPG cardiac liveness | `android/.../rppg/` | A | `RppgStream` |
| Fusion (NPU + rPPG) | `android/.../detection/Fusion.kt` | A | (internal) |
| Manifest schema | `android/.../api/Manifest.kt` | C | (data class) |
| JCS canonicalization | `android/.../jcs/` | C | `Jcs.canonicalize()` |
| Keystore + signing | `android/.../seal/Keystore.kt` | C | (internal) |
| EXIF embed | `android/.../seal/Exif.kt` | C | (internal) |
| DCT watermark | `android/.../dct/` | C | `DctWatermark.embed/extract` |
| Seal (orchestrator) | `android/.../seal/RealSealEngine.kt` | C | `SealEngine` |
| Verify (orchestrator) | `android/.../verify/RealVerifyEngine.kt` | C | `VerifyEngine` |
| Compose UI | `android/.../ui/` | B (integrator) | — |

---

## STATUS — autonomous build session #2 (2026-05-24)

**Everything writable from a keyboard is done.** What's left is gated on user-only actions: device boot, dataset approvals, AI Hub token, QNN AAR download. See `HANDOFF.md` for the ordered to-do list.

### ✅ Completed in this session
- All Compose UI screens: MainActivity, CameraScreen, VerdictOverlay, VerifyScreen, PermissionsScreen, PostCaptureScreen, SettingsScreen, nav state, share-intent routing.
- Resources: strings.xml, themes.xml, colors.xml, adaptive-icon launcher (vector), backup_rules.xml, data_extraction_rules.xml, assets/ placeholder.
- 9 Kotlin unit test classes: JcsTest, DctMathTest, DctWatermarkTest, ReedSolomonTest, YCbCrTest, FusionTest, PosAlgorithmTest, Sha256Test, ManifestSerializationTest.
- Python ML pipeline: `ml/src/{data,models,rppg,workbench}/`, `train.py`, `eval.py`, `reference_inference.py`, `gradcam.py`, `requirements.txt`, `notebooks/colab_train.ipynb`, `tests/`.
- Python CLI seal/verify under `tools/cli_sealverify/`: full pipeline, vector generator, round-trip tests, JCS cross-language pin.
- `tools/test_vectors/README.md` (vectors generated on demand via CLI).
- GitHub Actions: `.github/workflows/android.yml` + `python.yml`.
- Gradle wrapper properties + `WRAPPER-SETUP.md` (the jar itself bootstraps on first Studio sync or `gradle wrapper` command).
- Module READMEs: `android/README.md`, `ml/README.md`, `tools/cli_sealverify/README.md`.
- This `CLAUDE.md` refresh + `HANDOFF.md` for the user.

### ✅ Already done from prior session
- Repo infra (settings.gradle.kts, root build.gradle.kts, libs.versions.toml, gradle.properties)
- app/build.gradle.kts (Compose enabled, BuildConfig flags, ABI split for arm64-v8a)
- app/proguard-rules.pro
- app/libs/README.txt (QNN AAR drop instructions)
- AndroidManifest.xml (CAMERA + READ_MEDIA_* + share intent + no INTERNET)
- All API interfaces + DTOs (api/Engines.kt + Verdict + Manifest + CaptureMeta + VerifyResult + FrameInput)
- All 5 mocks (MockDetectionEngine, MockSealEngine, MockVerifyEngine, MockCameraEngine, MockRppgStream)
- PramanaApp.kt (DI with mock-fallback per interface)
- Engineer C: Sha256, Keystore (StrongBox→TEE→RSA), JCS wrapper, Exif, DCT (Math+Watermark+ReedSolomon+YCbCr), RealSealEngine, TrustStore, RealVerifyEngine
- Engineer A: PosAlgorithm, RealRppgStream, Fusion, PreprocessIntoTensor, TfliteRunner (with QNN stub), RealDetectionEngine
- Engineer B: RealCameraEngine (YUV + pre-allocated buffers + zero per-frame alloc), YuvToRgb
- Compose theme (Color, Theme, Type)

### 🔴 PLACEHOLDERS / `TODO(human):` markers

Grep `android/` for `TODO(human):`. Each tells you exactly what to do.

| What | Where | Why blocked |
|---|---|---|
| Drop `qnn-tflite-delegate.aar` into `android/app/libs/` | `android/app/libs/README.txt` | Qualcomm Developer Network gated download |
| Uncomment `implementation(files("libs/qnn-tflite-delegate.aar"))` | `android/app/build.gradle.kts` ~ line 157 | depends on the file above |
| Replace `tryQnn` stub with real QNN delegate init | `detection/TfliteRunner.kt::tryQnn` | bible §13 MANUAL — version-sensitive |
| AI Hub Workbench API token (`qai-hub configure --api_token <T>`) | env-only, never commit | account is gated |
| Drop trained `.tflite` into `android/app/src/main/assets/` (filenames `pramana-int8.tflite`, `pramana-int4.tflite`) | const set in `detection/TfliteRunner.kt` | needs training run + Workbench |
| Dataset download (FF++ + Celeb-DF + IIIT-CFW) | `ml/datasets/` | needs Kaggle CLI / approval emails |
| First-launch StrongBox availability test | runs on first launch on real device | needs phone |
| Bootstrap `gradlew` + `gradle-wrapper.jar` | `android/` | one-time: Android Studio does it on first sync, or `gradle wrapper --gradle-version 8.9` |
| MediaPipe Face Mesh ROI integration | `rppg/RealRppgStream.kt` (currently fixed ROI centers) | strong baseline works; full integration is polish |

### Where to resume

If you (next session or next account) are picking this up:

1. Read this section.
2. Read `HANDOFF.md` to see what's user-side.
3. Grep `TODO(human):` for code-side todos.
4. The bible (`docs/bible.md`) is the source of truth for any module you don't have full context on.
5. Every interface has a mock — the app is runnable in mock mode immediately after `:app:assembleDebug`.

---

## What NOT to do

- **Don't write Gradle from scratch** — already scaffolded; only edit the version catalog or `app/build.gradle.kts`.
- **Don't add cloud/backend code** — Pramāṇa is on-device only. No Firebase, no Supabase, no analytics.
- **Don't add network calls** in the production code path. Privacy-first is part of the pitch.
- **Don't make up QNN performance numbers.** The only valid latency numbers come from AI Hub Workbench profiling on real hardware.
- **Don't reach for `--demo-mode`** as a shortcut. Demo mode is a stage-rescue insurance policy, not a development aid.
- **Don't hand-roll JSON canonicalization.** RFC 8785 JCS library (`io.github.erdtman:java-json-canonicalization` on Kotlin, `jcs` on PyPI).
- **Don't ignore the "MANUAL" list in bible Section 13.** Stub it, leave a `// TODO(human):` comment, move on.
- **Don't duplicate the API surface.** All interfaces and DTOs live in `api/Engines.kt` + sibling DTO files. If you find yourself writing a parallel `DetectionEngine.kt`, stop — the existing one is the source of truth.
