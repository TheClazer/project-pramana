package io.teamsnapped.pramana.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.teamsnapped.pramana.ui.theme.SealVerifiedColor

/**
 * Confirmation card shown right after a successful seal. Reassures the
 * operator that the shutter actually produced a hardware-signed,
 * watermark-stamped file. Bible Section 7 UI affordance.
 */
@Composable
fun PostCaptureScreen(savedPath: String, keyId: String, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Sealed", style = MaterialTheme.typography.displaySmall,
            color = SealVerifiedColor)
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Saved to: $savedPath",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text("Signed by key: ${keyId.take(16)}…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "EXIF carries the Pramāṇa manifest. An invisible DCT watermark also encodes the manifest fingerprint, so the seal survives WhatsApp / Instagram / screenshot compression.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone) { Text("Back to camera") }
    }
}
