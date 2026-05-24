// Top-level build file. Module-specific config lives in app/build.gradle.kts.
//
// If Android Studio prompts you to bump these versions on Gradle sync, accept its
// suggestion — these are pinned to a known-stable set for the bible's v1.1 scaffold.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
