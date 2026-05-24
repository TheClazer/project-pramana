package io.teamsnapped.pramana.ui.screens

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.teamsnapped.pramana.api.CameraEngine
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.SealedFile
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.VerdictLabel
import io.teamsnapped.pramana.ui.theme.PramanaPrimary
import io.teamsnapped.pramana.ui.theme.VerdictFake
import io.teamsnapped.pramana.ui.theme.VerdictGenuine
import io.teamsnapped.pramana.ui.theme.VerdictSuspicious
import io.teamsnapped.pramana.ui.theme.VerdictUnknown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Primary screen — live camera + verdict overlay + shutter.
 *
 * Bible Section 9. The CameraEngine emits verdicts on a SharedFlow; we
 * collectAsState and feed them to [VerdictOverlay].
 *
 * Shutter flow:
 *  1. Capture still through CameraEngine (which seals internally).
 *  2. Persist sealed bytes to MediaStore (Pictures/Pramana).
 *  3. Navigate to PostCaptureScreen with the saved URI + keyId for receipt.
 */
@Composable
fun CameraScreen(
    cameraEngine: CameraEngine,
    detectionEngine: DetectionEngine,
    onOpenVerify: () -> Unit,
    onOpenSettings: () -> Unit,
    onSealed: (savedPath: String, keyId: String) -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var captureInProgress by rememberSaveable { mutableStateOf(false) }
    val verdict by cameraEngine.verdictBus.collectAsState(initial = null)

    // The PreviewView we hand to CameraX. AndroidView creates one Android View
    // bridged into the Compose tree.
    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        // Wire the live surface BEFORE starting — otherwise the Preview use
        // case binds with no surface and the screen stays black.
        cameraEngine.attachPreview(previewView)
        cameraEngine.start(lifecycleOwner)
        onDispose {
            cameraEngine.stop()
            cameraEngine.attachPreview(null)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Top HUD — backend label + verdict chip
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackendChip(label = cameraEngine.backend())
            Spacer(Modifier.width(12.dp))
            verdict?.let { VerdictChip(it) }
            Spacer(Modifier.fillMaxWidth(0.5f))
        }

        // Verdict overlay — large status visible to the camera operator
        VerdictOverlay(
            verdict = verdict,
            modifier = Modifier.align(Alignment.Center)
        )

        // Bottom controls
        Row(
            modifier = Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onOpenVerify) { Text("Verify file") }

            ShutterButton(
                enabled = !captureInProgress && verdict?.label != VerdictLabel.FAKE,
                onClick = {
                    captureInProgress = true
                    cameraEngine.captureStill { sealed ->
                        scope.launch {
                            val savedUri = withContext(Dispatchers.IO) {
                                sealed?.let { saveToMediaStore(ctx.applicationContext, it) }
                            }
                            captureInProgress = false
                            if (sealed != null && savedUri != null) {
                                onSealed(savedUri, sealed.manifest.keyId)
                            }
                        }
                    }
                }
            )

            Button(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val container = if (enabled) PramanaPrimary else VerdictUnknown
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(container, shape = CircleShape)
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(if (enabled) "●" else "✕", color = Color.Black, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun BackendChip(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun VerdictChip(v: Verdict) {
    val color = when (v.label) {
        VerdictLabel.GENUINE    -> VerdictGenuine
        VerdictLabel.SUSPICIOUS -> VerdictSuspicious
        VerdictLabel.FAKE       -> VerdictFake
    }
    Box(
        modifier = Modifier
            .background(color)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            "${v.label}  ${"%.0fms".format(v.latencyMs.toFloat())}",
            color = Color.Black,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/**
 * Save sealed bytes into MediaStore under Pictures/Pramana/. Returns the
 * MediaStore Uri (or absolute file path on pre-Q) as a string.
 */
private fun saveToMediaStore(ctx: android.content.Context, sealed: SealedFile): String? {
    val ts = System.currentTimeMillis()
    val name = "pramana_$ts.jpg"
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Pramana")
        }
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
    val stream: OutputStream = resolver.openOutputStream(uri) ?: return null
    stream.use { it.write(sealed.bytes) }
    return uri.toString()
}
