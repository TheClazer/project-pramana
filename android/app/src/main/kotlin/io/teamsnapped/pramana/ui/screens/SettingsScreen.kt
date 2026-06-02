package io.teamsnapped.pramana.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.teamsnapped.pramana.BuildConfig
import io.teamsnapped.pramana.api.DetectionEngine
import io.teamsnapped.pramana.api.SealEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings + about screen. Exposes:
 *  - active detection backend (NPU/GPU/CPU/MOCK) + a LIVE backend switcher for the
 *    NPU-vs-CPU latency A/B demo (bible: the strongest single proof the NPU is real)
 *  - device public key fingerprint (for cross-device trust-store seeding)
 *  - demo-mode flag (bible Section 5 Pattern 3 — read-only here, by design)
 */
@Composable
fun SettingsScreen(
    backendLabel: String,
    sealEngine: SealEngine,
    detectionEngine: DetectionEngine,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pubKey by remember { mutableStateOf("loading…") }
    var liveBackend by remember { mutableStateOf(backendLabel) }
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        pubKey = try { sealEngine.publicKeyB64().take(32) + "…" } catch (t: Throwable) { "unavailable" }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row("Backend (active)", liveBackend)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (switching) "switching…" else "Force backend — re-runs the model on this tier (NPU↔CPU latency A/B):",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (mode in listOf("AUTO", "NPU", "GPU", "CPU")) {
                        Button(
                            enabled = !switching,
                            onClick = {
                                switching = true
                                scope.launch {
                                    val reached = withContext(Dispatchers.Default) {
                                        detectionEngine.forceBackend(mode)
                                    }
                                    liveBackend = reached
                                    switching = false
                                }
                            }
                        ) { Text(mode, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row("Model", BuildConfig.DETECTION_MODEL_ID)
                Spacer(Modifier.height(12.dp))
                Row("Demo mode", BuildConfig.DEMO_MODE_DEFAULT.toString())
                Spacer(Modifier.height(12.dp))
                Row("Public key (truncated)", pubKey)
                Spacer(Modifier.height(12.dp))
                Row("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
        }
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("About Pramāṇa",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hardware-rooted on-device deepfake detection and authenticity provenance. " +
                        "Detection runs on the Snapdragon Hexagon NPU. Sealing uses ECDSA-P256 " +
                        "in StrongBox (or TEE). Two-layer authenticity: EXIF manifest + invisible " +
                        "DCT pixel watermark. No cloud, no telemetry.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
