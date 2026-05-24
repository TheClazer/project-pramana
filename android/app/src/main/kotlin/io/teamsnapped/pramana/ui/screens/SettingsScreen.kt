package io.teamsnapped.pramana.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.teamsnapped.pramana.BuildConfig
import io.teamsnapped.pramana.api.SealEngine

/**
 * Settings + about screen. Exposes:
 *  - active detection backend (NPU/GPU/CPU/MOCK)
 *  - device public key fingerprint (for cross-device trust-store seeding)
 *  - demo-mode flag (bible Section 5 Pattern 3 — read-only here; toggling
 *    requires app restart for now, by design)
 */
@Composable
fun SettingsScreen(
    backendLabel: String,
    sealEngine: SealEngine,
    onBack: () -> Unit
) {
    var pubKey by remember { mutableStateOf("loading…") }
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
                Row("Backend", backendLabel)
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
