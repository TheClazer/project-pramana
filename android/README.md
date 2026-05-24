# android/ — Pramāṇa Android app

Kotlin + Compose. Single-module app (`:app`). All three engineering tracks
live here: Engineer A (detection, rPPG, fusion), Engineer B (camera, UI,
runner), Engineer C (crypto, manifest, EXIF, DCT, verify).

## Layout

```
android/
├── settings.gradle.kts
├── build.gradle.kts          root
├── gradle.properties
├── gradle/libs.versions.toml version catalog — change deps HERE
├── WRAPPER-SETUP.md          one-time gradle-wrapper.jar bootstrap
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── libs/                 QNN TFLite Delegate AAR drop-in (gitignored binary)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/             pramana-int8.tflite goes here
        │   ├── res/
        │   └── kotlin/io/teamsnapped/pramana/
        │       ├── PramanaApp.kt       Application DI
        │       ├── api/                interfaces + DTOs (the contracts)
        │       ├── camera/             Engineer B — CameraX YUV pipeline
        │       ├── detection/          Engineer A — TFLite + rPPG fusion
        │       ├── rppg/               Engineer A — POS + FFT
        │       ├── seal/               Engineer C — Keystore, EXIF, RealSealEngine
        │       ├── verify/             Engineer C — RealVerifyEngine
        │       ├── jcs/                Engineer C — RFC 8785 wrapper
        │       ├── dct/                Engineer C — 8×8 DCT watermark + RS
        │       ├── mocks/              every interface has a mock
        │       └── ui/                 Compose screens + theme + nav
        └── test/                   JUnit unit tests for crypto/DCT/JCS/Fusion/POS
```

## Build + install on the iQOO Z5

```powershell
# 1. (one-time) bootstrap the Gradle wrapper if not present
cd D:\Work\project-pramana\android
gradle wrapper --gradle-version 8.9      # or let Android Studio do it on first sync

# 2. Confirm the phone is reachable
adb devices                              # should show your Z5 serial

# 3. Build + install + launch
.\gradlew :app:installDebug
adb shell am start -n io.teamsnapped.pramana/.ui.MainActivity
```

## Run unit tests

```powershell
.\gradlew :app:testDebugUnitTest
```

Tests cover: JCS canonicalization, DCT round-trip, Reed-Solomon, YCbCr,
Fusion hard-veto rule, POS algorithm, SHA-256 vectors, Manifest serialization.

## Engineer ownership

| Module | Owner | What it does |
|---|---|---|
| `api/` | All | The interface contracts. Don't change without consensus. |
| `camera/` | B | CameraX YUV_420_888, pre-allocated ByteBuffers, ImageCapture. |
| `detection/` | A | TfliteRunner with QNN→GPU→CPU ladder, PreprocessIntoTensor, RealDetectionEngine, Fusion. |
| `rppg/` | A | POS algorithm + 30-frame sliding FFT. |
| `seal/` | C | Keystore (StrongBox→TEE→RSA), Sha256, EXIF, RealSealEngine, TrustStore. |
| `verify/` | C | RealVerifyEngine — five VerifyResult states. |
| `jcs/` | C | RFC 8785 wrapper around Erdtman library. |
| `dct/` | C | 8×8 DCT math + watermark embed/extract + Reed-Solomon GF(256). |
| `mocks/` | All | One mock per interface; the app boots end-to-end in pure mock mode. |
| `ui/` | B (integrator) | Compose root, screens, theme, nav. |

## TODOs left for the human (search for `TODO(human):`)

1. `seal/Keystore.kt` — confirm StrongBox feature on first device launch
2. `detection/TfliteRunner.kt::tryQnn` — drop in the real QNN delegate init
   once `app/libs/qnn-tflite-delegate.aar` is in place
3. `rppg/RealRppgStream.kt` — wire MediaPipe FaceLandmarker to populate the
   ROI centers from actual face landmarks (currently fixed positions)
