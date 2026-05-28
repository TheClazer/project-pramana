# Pramāṇa — Core ML & On-Device Deployment Guide

Welcome to the definitive deployment guide for **Pramāṇa**. This document explains the exact scientific and engineering reasons behind our multi-dataset approach, why the models are needed, and gives you a step-by-step checklist to follow from the moment you clone the GitHub repository onto your Windows 11 machine.

---

## ❓ Part 1: Why Do We Need Both FaceForensics++ and Celeb-DF v2?

Training a deepfake detection model is highly susceptible to **overfitting** (where the model memorizes the specific video quality and artifacts of one dataset but fails completely in the real world). 

To ensure the model is robust and reliable during your live hackathon demo, we train on a combination of **FaceForensics++** and **Celeb-DF v2**:

| Feature / Aspect | FaceForensics++ (FF++) | Celeb-DF v2 |
| :--- | :--- | :--- |
| **Generation Methods** | Classic methods (Deepfakes, FaceSwap, Face2Face, NeuralTextures) | Modern Autoencoder-based deepfakes with refined blending boundaries |
| **Realism** | Medium (has noticeable blending/color artifacts at the edges of the face) | **High** (extremely realistic, reduced color mismatch, crisp details) |
| **Video Quality** | High compression (`c23` benchmark) | High definition with minimal compression artifacts |
| **What it teaches the model** | It teaches the model to look for texture blending artifacts and face splicing boundaries. | It teaches the model to look for highly subtle structural anomalies and artificial facial motion. |
| **Demographics** | Mostly Western/Caucasian faces | Diverse, but still lacking high representation of Asian/Indian faces |

### The "Generalization Gap" Danger ⚠️
If you train a model *only* on FF++, its accuracy on Celeb-DF drops to **under 50%** (no better than a coin flip). By combining both datasets during training (along with **IIIT-CFW** to normalize demographic bias for Indian faces), you create a model that generalizes beautifully to the cameras, skin tones, and lighting environments in your actual hackathon room.

---

## ❓ Part 2: Why Are These Models Needed?

Pramāṇa employs a **dual-model security architecture** combining **Static AI** and **Dynamic Liveness**:

### 1. MobileNet-V3 Deepfake Classifier (Static Engine)
*   **What it is:** A lightweight, 8-bit quantized convolutional neural network running on Qualcomm's NPU.
*   **Why it's needed:** It performs instantaneous, per-frame visual analysis. It looks for splicing artifacts, artificial skin textures, ear/hair boundary mismatches, and eye-reflection anomalies that human eyes cannot catch.
*   **Performance goal:** Sub-3ms inference latency utilizing Snapdragon HTP (Hexagon Tensor Processor).

### 2. rPPG Cardiac Liveness Monitor (Dynamic Engine)
*   **What it is:** An algorithmic processor that measures subtle, microscopic blood-volume changes in facial skin pixels caused by your heartbeat (remote Photoplethysmography).
*   **Why it's needed:** Even if a deepfake is visually perfect, it cannot replicate a real human heartbeat. If someone holds up a high-resolution printed photo, an iPad showing a deepfake video, or a realistic 3D silicon mask, the **rPPG module will flag it as a FAKE** because it detects a flat, flat-lined cardiac signal.
*   **How they fuse:** The app combines the deepfake classifier score with the rPPG cardiac SNR score using a **2-second hard-veto rule** (if rPPG fails to detect a heart signal for 2 seconds, the frame is immediately flagged as `SUSPICIOUS` or `FAKE`, regardless of how realistic the face looks).

---

## 📋 Part 3: Step-by-Step Guide After Cloning the Repo

Follow these exact steps from the moment you have cloned `https://github.com/TheClazer/project-pramana.git` onto your Windows 11 machine.

### Phase A: Device Setup & First Test Run (Mock Mode)

#### 1. Open in Android Studio
1. Launch Android Studio.
2. Select **Open** and choose the folder:
   ```
   D:\Work\project-pramana\android
   ```
3. Let the Gradle sync run. When prompted to generate the Gradle wrapper, select **Yes** (or open PowerShell in the `android/` directory and run `.\gradlew wrapper --gradle-version 8.9`).

#### 2. Enable Developer Settings on your iQOO Z5
1. Go to **Settings -> About Phone -> tap "Build Number" 7 times** to unlock Developer Options.
2. Go to **Settings -> System -> Developer Options** and enable:
   * **USB Debugging**
   * **Install via USB**

#### 3. Run the App Immediately
1. Connect the phone via USB.
2. In Android Studio's top bar, select your **iQOO Z5** device.
3. Click the green **Run** icon (or run `.\gradlew :app:installDebug` from PowerShell).
4. The app will install and open in **mock mode**. Verify that:
   * The camera feed opens and shows the verdict overlay.
   * You can press the shutter to sign a mock frame, canonicalize it using JCS, and embed it into EXIF.
   * The verify screen extracts and checks the signature against the TrustStore.

---

### Phase B: Datasets & API Token Collection

#### 4. Register for Datasets (Do this immediately!)
*   Submit requests to download [FaceForensics++](https://github.com/ondyari/FaceForensics) and [Celeb-DF v2](https://github.com/yuezunli/celeb-deepfakeforensics) using your institutional email.
*   Once approved, download the zip files and extract them into your local machine under:
    ```
    D:\Work\project-pramana\ml\datasets\faceforensics\
    D:\Work\project-pramana\ml\datasets\celebdf\
    ```

#### 5. Get your Qualcomm AI Hub Token
1. Go to [Qualcomm AI Hub](https://aihub.qualcomm.com/) and register.
2. Navigate to **Settings -> API Tokens -> Generate Token** and copy the string.

---

### Phase C: Model Training & Quantization

#### 6. Run the Local Pipeline Smoke Test
Verify the Python ML environment is correctly configured on your Windows machine:
1. Open PowerShell and run:
   ```powershell
   cd D:\Work\project-pramana
   python3 -m venv test_env
   .\test_env\Scripts\Activate.ps1
   pip install -r ml/requirements.txt
   ```
2. Run the quick smoke test:
   ```powershell
   python -m ml.src.train --dataset stand_in --epochs 1 --quick
   ```
   *This trains the pipeline on synthetic images in ~1 minute to verify everything is working.*

#### 7. Full Training on Google Colab (Highly Recommended)
1. Upload the folder containing your approved datasets to your Google Drive.
2. Upload the notebook `ml/notebooks/colab_train.ipynb` to [Google Colab](https://colab.research.google.com/).
3. Set the runtime type to **GPU**.
4. Run all cells. It will train the model on the full combined dataset and output `best.pt` directly to your Google Drive. Download this file to your PC.

#### 8. Compile and Quantize for Snapdragon NPU
In your local PowerShell (with `test_env` active), run the compilation pipeline:
```powershell
# Set up your Qualcomm Hub Token
qai-hub configure --api_token <YOUR_COPIED_TOKEN>

# 1. Compile PyTorch model (returns compile_job_id)
python -m ml.src.workbench.compile --checkpoint best.pt --backbone mobilenet_v3_small

# 2. Quantize model to high-performance INT8 (produces pramana-int8.tflite)
python -m ml.src.workbench.quantize --compile-job-id <YOUR_COMPILE_JOB_ID> --precision int8
```

---

### Phase D: NPU & MediaPipe Activation

#### 9. Place assets inside the Android App
Take the generated `pramana-int8.tflite` model and copy it directly to:
```
android/app/src/main/assets/pramana-int8.tflite
```

#### 10. Activate Qualcomm Hexagon NPU Delegate
1. Log in to Qualcomm Developer Network and download the **Qualcomm AI Engine Direct SDK** (QNN SDK).
2. Extract the SDK, locate the `qnn-tflite-delegate.aar` binary, and copy it directly to:
   ```
   android/app/libs/qnn-tflite-delegate.aar
   ```
3. Open `android/app/build.gradle.kts` and verify line 158 is uncommented:
   ```kotlin
   implementation(files("libs/qnn-tflite-delegate.aar"))
   ```

#### 11. Activate Face Mesh ROI Tracking
1. Download `face_landmarker.task` from Google MediaPipe's official site.
2. Drop it inside the assets folder:
   ```
   android/app/src/main/assets/face_landmarker.task
   ```

#### 12. Build and Deploy your Final NPU-Powered App!
1. Press **Run** in Android Studio (or run `.\gradlew :app:installDebug`).
2. **Success!** Your app is now running your custom-trained deepfake model directly on the **Hexagon NPU** at sub-3ms latency, with full real-time facial landmark tracking! 🚀
