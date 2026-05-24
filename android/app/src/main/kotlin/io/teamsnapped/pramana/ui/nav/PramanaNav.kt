package io.teamsnapped.pramana.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny navigation state — Compose Navigation pulls in too many deps for what
 * we need. Bible Section 9: Engineer B owns this; minimal is better.
 *
 * Five screens:
 *  - [Screen.Camera]            live camera + verdict overlay (primary)
 *  - [Screen.Verify]            chooser for "verify a file from gallery"
 *  - [Screen.VerifyFromUri]     deep-linked via share-intent
 *  - [Screen.PostCapture]       confirmation card after a successful seal
 *  - [Screen.Settings]          backend label, demo-mode toggle, about
 */
sealed class Screen {
    data object Camera : Screen()
    data object Verify : Screen()
    data class VerifyFromUri(val uri: String) : Screen()
    data class PostCapture(val savedPath: String, val manifestKeyId: String) : Screen()
    data object Settings : Screen()
}

class PramanaNav(initial: Screen) {
    var current: Screen by mutableStateOf(initial)
        private set

    fun goTo(screen: Screen) { current = screen }
    fun back() {
        current = when (current) {
            is Screen.Verify, is Screen.VerifyFromUri,
            is Screen.PostCapture, is Screen.Settings -> Screen.Camera
            else -> Screen.Camera
        }
    }
}
