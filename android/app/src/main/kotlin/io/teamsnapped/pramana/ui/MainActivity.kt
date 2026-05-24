package io.teamsnapped.pramana.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.teamsnapped.pramana.PramanaApp
import io.teamsnapped.pramana.ui.nav.PramanaNav
import io.teamsnapped.pramana.ui.nav.Screen
import io.teamsnapped.pramana.ui.theme.PramanaTheme

/**
 * Single-activity host for the Compose UI. Owns:
 *  - permissions request (CAMERA + READ_MEDIA_*),
 *  - share-intent → Verify deep link (bible Section 3 mapping),
 *  - construction of the [io.teamsnapped.pramana.api.CameraEngine] (which
 *    needs a LifecycleOwner, hence built here rather than in PramanaApp).
 *
 * Bible Section 9. UI integrator (Engineer B) hat from day 17.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as PramanaApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build the per-activity CameraEngine — needs an Activity context + lifecycle.
        val cameraEngine = app.makeCameraEngine()

        val initialScreen = resolveScreenFromIntent(intent)

        setContent {
            PramanaTheme {
                val nav = remember { PramanaNav(initial = initialScreen) }

                var cameraPermissionGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { results ->
                        cameraPermissionGranted =
                            results[Manifest.permission.CAMERA] == true
                    }
                )

                LaunchedEffect(Unit) {
                    val needed = mutableListOf<String>()
                    if (!cameraPermissionGranted) needed += Manifest.permission.CAMERA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        needed += Manifest.permission.READ_MEDIA_IMAGES
                        needed += Manifest.permission.READ_MEDIA_VIDEO
                    }
                    if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
                }

                PramanaApp_UIRoot(
                    nav = nav,
                    cameraEngine = cameraEngine,
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCamera = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    },
                    verifyEngine = app.verifyEngine,
                    sealEngine = app.sealEngine,
                    detectionEngine = app.detectionEngine
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch with the new intent — Compose will read it via remember.
        setIntent(intent)
        recreate()
    }

    private fun resolveScreenFromIntent(intent: Intent?): Screen {
        if (intent == null) return Screen.Camera
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        return if (uri != null) Screen.VerifyFromUri(uri.toString()) else Screen.Camera
    }
}
