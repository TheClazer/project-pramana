# Setup checklist

What **you** (the human) must do that I (Claude) cannot. Work through this in parallel while the code is in your repo.
The full ordered to-do list with build commands is in [`HANDOFF.md`](../HANDOFF.md).

---

## 🔴 BLOCKING (do these on day 1)

### 1. Install Android Studio
- Download: https://developer.android.com/studio (latest stable)
- During install, accept all default SDKs (the wizard handles JDK, Android SDK, build-tools).
- Once installed, open this project: `File → Open → D:\Work\project-pramana\android`. Let it sync Gradle. First sync takes 5–10 minutes and will create the missing `gradlew` + `gradle-wrapper.jar`.
- **If Gradle sync fails with an SDK error**: `File → Project Structure → SDK Location` → set Android SDK to the path Studio installed it (usually `C:\Users\Rayyan Shaikh\AppData\Local\Android\Sdk`).

### 2. Enable USB debugging on the iQOO Z5
- Settings → About phone → tap "Build number" 7 times.
- Settings → Additional settings → Developer options → toggle **USB debugging** ON.
- Plug into laptop with a **data-capable** USB cable.
- Tap "Allow USB debugging?" when it pops up, check "Always allow from this computer".
- Verify in PowerShell: `adb devices` should show your phone's serial.

### 3. Sign up for Qualcomm AI Hub
- https://aihub.qualcomm.com → sign up.
- Settings → API Tokens → generate a new token.
- One-time configure on your laptop:
  ```powershell
  pip install qai-hub qai-hub-models
  qai-hub configure --api_token <YOUR_TOKEN>
  python -c "import qai_hub; print(qai_hub.get_devices()[:3])"
  ```
- Token is persisted at `C:\Users\Rayyan Shaikh\.qai_hub\client.ini` — never paste in chat.

### 4. Apply for FaceForensics++
- https://github.com/ondyari/FaceForensics → fill the request form.
- **Use your IEEE RVCE / college email** for fastest approval. 1–7 days.
- Once approved, download the **c23 compressed** split (~30 GB). Skip raw (huge) and c40 (too compressed).
- Drop into `ml\datasets\faceforensics\`.

---

## 🟡 IMPORTANT (do these by day 3)

### 5. Apply for Celeb-DF v2
- https://github.com/yuezunli/celeb-deepfakeforensics → fill the form.
- Drop into `ml\datasets\celebdf\`.

### 6. Install Python tooling
You already have Python 3.11. From the repo root:
```powershell
pip install -r ml/requirements.txt
pip install -r tools/cli_sealverify/requirements.txt
```
If pip complains about Visual C++ build tools, install "Build Tools for Visual Studio 2022" from microsoft.com/visualstudio/downloads.

### 7. Google Colab account
- Sign in to https://colab.research.google.com.
- Open `ml/notebooks/colab_train.ipynb`. Free T4 GPU is enough for MobileNet-V3-Small.

### 8. Push to GitHub
The git repo is already initialized and your remote at https://github.com/TheClazer/project-pramana already exists.
```powershell
git remote add origin https://github.com/TheClazer/project-pramana.git
git branch -M main
git add -A
git commit -m "Initial Pramāṇa scaffold (Phase 0–6)"
git push -u origin main
```
GitHub Actions fires immediately — Android assembleDebug + tests + Python CLI tests.

---

## 🟢 NICE-TO-HAVE (do these by day 7)

### 9. Get IIIT-CFW dataset for Indian face augmentation
- https://cvit.iiit.ac.in/research/projects/cvit-projects/cartoonfaces
- Drop into `ml\datasets\iiitcfw\`. No approval needed.

### 10. Borrow a Snapdragon 8 Gen 2/3 phone (per v1.1 bible Engineer B deliverable)
- Your iQOO Z5 (SD 778G) is good for dev. But the bible's pitch numbers target 8 Gen 2/3.
- Ask classmates / IEEE RVCE chapter / college lab: Galaxy S22+/S23/S24, Xiaomi 13/14/15, OnePlus 11/12, RB3 Gen 2 dev kit.
- Even 2 hours on a flagship device gives you a flagship QNN profile screenshot for the pitch slide.

### 11. Download the QNN TFLite Delegate AAR
- Qualcomm Developer Network → "Qualcomm AI Engine Direct SDK" → QNN TFLite Delegate AAR.
- Drop at `android\app\libs\qnn-tflite-delegate.aar`.
- Uncomment `implementation(files("libs/qnn-tflite-delegate.aar"))` in `android\app\build.gradle.kts`.
- Open `detection/TfliteRunner.kt` and replace the `tryQnn` stub with the version-specific init from the `ai-hub-apps` sample.

### 12. Pre-load offline backups
- Per bible Section 11: pre-load the demo gallery, model files, and the backup demo video onto every laptop's local drive. Wi-Fi at venues fails.

---

## What I (Claude) do NOT need from you

- Cloud accounts (AWS/GCP/Azure/Supabase) — Pramāṇa is fully on-device, no backend.
- Payment for anything — every tool above is free for our usage.
- Datadog/Sentry/analytics — privacy-first means no telemetry.
- API keys for external services other than AI Hub Workbench.

---

## Track your status here

Edit this section as you complete items. Future Claude sessions read it at start.

```
[ ] Android Studio installed and Gradle sync passing
[ ] iQOO Z5 visible in `adb devices`
[ ] AI Hub Workbench account + API token configured (qai-hub configure done)
[ ] First mock-mode install on Z5 succeeded
[ ] FaceForensics++ approval received + downloaded
[ ] Celeb-DF v2 approval received + downloaded
[ ] Python ml deps installed
[ ] GitHub remote set + initial push done
[ ] IIIT-CFW downloaded
[ ] Stand-in training run completed (smoke test)
[ ] Full training run completed (AUC > 0.85 on FF++ val)
[ ] AI Hub Workbench compile + INT8 quantize + profile completed
[ ] AI Hub Workbench INT4 quantize attempted
[ ] pramana-int8.tflite dropped into android/app/src/main/assets/
[ ] QNN TFLite Delegate AAR downloaded + dropped into android/app/libs/
[ ] tryQnn() stub replaced with real init
[ ] Snapdragon 8 Gen 2/3 phone borrowed (at least once)
[ ] Demo gallery curated (8–12 known-handleable samples)
[ ] Offline demo backup video recorded
```
