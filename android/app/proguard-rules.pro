# Pramāṇa — ProGuard / R8 rules
#
# We only ship debug builds for the hackathon, so this file is mostly defensive.

# Keep our public interface contracts (other engineers / future codebases may
# load via reflection).
-keep class io.teamsnapped.pramana.api.** { *; }
-keep interface io.teamsnapped.pramana.api.** { *; }

# Keep all @Serializable data classes — kotlinx-serialization uses reflection on
# constructors / fields.
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }

# TFLite needs its native bridges intact.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.gpu.**

# QNN delegate (loaded from local AAR) — keep everything.
-keep class com.qualcomm.qti.qnn.** { *; }
-dontwarn com.qualcomm.qti.qnn.**

# MediaPipe Tasks
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# JTransforms
-keep class org.jtransforms.** { *; }
-dontwarn org.jtransforms.**

# JCS (Erdtman canonicalizer)
-keep class org.erdtman.jcs.** { *; }
-dontwarn org.erdtman.jcs.**

# Compose
-keep class androidx.compose.** { *; }
