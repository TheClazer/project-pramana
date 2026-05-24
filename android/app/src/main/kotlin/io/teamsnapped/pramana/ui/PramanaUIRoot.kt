package io.teamsnapped.pramana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.teamsnapped.pramana.api.CameraEngine
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.SealEngine
import io.teamsnapped.pramana.api.VerifyEngine
import io.teamsnapped.pramana.ui.nav.PramanaNav
import io.teamsnapped.pramana.ui.nav.Screen
import io.teamsnapped.pramana.ui.screens.CameraScreen
import io.teamsnapped.pramana.ui.screens.PermissionsScreen
import io.teamsnapped.pramana.ui.screens.PostCaptureScreen
import io.teamsnapped.pramana.ui.screens.SettingsScreen
import io.teamsnapped.pramana.ui.screens.VerifyScreen

/**
 * The top-level Compose graph. Routes between screens via [PramanaNav] state.
 *
 * Bible Section 9 v1.1: Engineer B is the UI integrator and merges UI from
 * A and C. This file is the merge point.
 */
@Composable
fun PramanaApp_UIRoot(
    nav: PramanaNav,
    cameraEngine: CameraEngine,
    cameraPermissionGranted: Boolean,
    onRequestCamera: () -> Unit,
    verifyEngine: VerifyEngine,
    sealEngine: SealEngine,
    detectionEngine: DetectionEngine
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val screen = nav.current) {
                is Screen.Camera -> {
                    if (cameraPermissionGranted) {
                        CameraScreen(
                            cameraEngine = cameraEngine,
                            detectionEngine = detectionEngine,
                            onOpenVerify = { nav.goTo(Screen.Verify) },
                            onOpenSettings = { nav.goTo(Screen.Settings) },
                            onSealed = { savedPath, keyId ->
                                nav.goTo(Screen.PostCapture(savedPath, keyId))
                            }
                        )
                    } else {
                        PermissionsScreen(onRequest = onRequestCamera)
                    }
                }
                is Screen.Verify -> VerifyScreen(
                    verifyEngine = verifyEngine,
                    initialUri = null,
                    onBack = { nav.back() }
                )
                is Screen.VerifyFromUri -> VerifyScreen(
                    verifyEngine = verifyEngine,
                    initialUri = screen.uri,
                    onBack = { nav.back() }
                )
                is Screen.PostCapture -> PostCaptureScreen(
                    savedPath = screen.savedPath,
                    keyId = screen.manifestKeyId,
                    onDone = { nav.back() }
                )
                is Screen.Settings -> SettingsScreen(
                    backendLabel = cameraEngine.backend(),
                    onBack = { nav.back() },
                    sealEngine = sealEngine
                )
            }
        }
    }
}
