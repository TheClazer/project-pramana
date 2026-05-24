Drop the QNN TFLite Delegate AAR here.

Filename expected by app/build.gradle.kts:
    qnn-tflite-delegate.aar

Where to download:
    Qualcomm Developer Network → "Qualcomm AI Engine Direct SDK" →
    QNN TFLite Delegate / Hexagon delegate AAR.
    (Exact path changes per release. Look for the AAR matching the QNN SDK
    version that AI Hub Workbench produced your .tflite under.)

Why this isn't on Maven:
    Qualcomm distributes the delegate behind a developer-account download.
    The bible (Section 13) flags QNN TFLite Delegate init as MANUAL — the
    AAR + the Java/Kotlin init code are both version-sensitive and not
    safely vibe-coded.

After dropping the AAR:
    1. Uncomment the `implementation(files("libs/qnn-tflite-delegate.aar"))`
       line in app/build.gradle.kts.
    2. Sync Gradle.
    3. The TfliteRunner in detection/ will pick it up via the ServiceLoader
       path used by Qualcomm's official sample.

This file is committed; the AAR itself is gitignored.
