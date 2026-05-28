//
// Pramāṇa — :app module
//
// Bible-pinned: minSdk 28 (Android 9), targetSdk 34 (Android 14), Kotlin + Compose.
// QNN TFLite Delegate AAR is expected at app/libs/qnn-tflite-delegate.aar — that
// drop-in lives outside Maven (Qualcomm Developer Network). See setup-checklist.md.
//

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "io.teamsnapped.pramana"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.teamsnapped.pramana"
        minSdk        = 28
        targetSdk     = 34
        versionCode   = 1
        versionName   = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // BuildConfig flags the app reads at runtime.
        // --demo-mode insurance (bible Section 5 Pattern 3) — default OFF.
        buildConfigField("boolean", "DEMO_MODE_DEFAULT", "false")

        // Model id used in manifest's detection.model field. Engineer A bumps this
        // when delivering a new tflite drop.
        buildConfigField("String", "DETECTION_MODEL_ID", "\"pramana-mobilenet-v3-small-int8-v1\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_VERBOSE", "true")
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "DEBUG_VERBOSE", "false")
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Keep warnings honest. We are NOT shipping to Play Store; -Werror is fine.
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    // Native libs ABI filter — QNN delegate ships arm64-v8a only. Snapdragon devices
    // are all arm64, so we explicitly drop armeabi-v7a + x86 to keep the APK small.
    splits {
        abi {
            isEnable           = false   // single fat APK for hackathon; flip later
            reset()
            include("arm64-v8a")
            isUniversalApk     = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt"
            )
        }
        // TFLite + MediaPipe ship native .so files; don't try to compress them.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets {
        getByName("main") {
            // Local AAR drop-ins (QNN TFLite Delegate) live in app/libs/.
            // See gitignored README at app/libs/README.txt for the exact filename.
        }
    }
}

dependencies {
    // -- Core / lifecycle -------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    // -- Compose ----------------------------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.pv)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)

    // -- CameraX ----------------------------------------------------------
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)

    // -- EXIF / imaging ---------------------------------------------------
    implementation(libs.androidx.exifinterface)

    // -- Coroutines -------------------------------------------------------
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // -- JSON + JCS -------------------------------------------------------
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jcs)

    // -- TFLite + delegates ----------------------------------------------
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)
    implementation(libs.tflite.gpu.delegate.plugin)
    implementation(libs.tflite.support)

    // QNN TFLite Delegate — local AAR drop-in. The settings.gradle.kts already
    // registers flatDir { dirs("app/libs") }. The file is gitignored; see
    // setup-checklist.md for download instructions.
    implementation(files("libs/qnn-tflite-delegate.aar"))   // <-- uncomment when AAR present

    // -- MediaPipe Face Mesh (rPPG ROIs) ---------------------------------
    implementation(libs.mediapipe.tasks.vision)

    // -- FFT for rPPG -----------------------------------------------------
    implementation(libs.jtransforms)

    // -- Tests ------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
