package io.teamsnapped.pramana.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.teamsnapped.pramana.api.BrokenSealReason
import io.teamsnapped.pramana.api.VerifyEngine
import io.teamsnapped.pramana.api.VerifyResult
import io.teamsnapped.pramana.ui.theme.SealBrokenColor
import io.teamsnapped.pramana.ui.theme.SealModifiedColor
import io.teamsnapped.pramana.ui.theme.SealNoneColor
import io.teamsnapped.pramana.ui.theme.SealVerifiedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Verify path. Either:
 *  - opened from share-intent (initialUri supplied) — runs immediately, or
 *  - opened from the camera screen ("Verify file" button) — user picks via
 *    SAF GetContent and we run on the result.
 *
 * Bible Section 8 — five badge states.
 */
@Composable
fun VerifyScreen(
    verifyEngine: VerifyEngine,
    initialUri: String?,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = UiState.Running
        scope.launch {
            val (bytes, mime) = withContext(Dispatchers.IO) { readUri(ctx, uri) }
                ?: (null to null)
            if (bytes == null) {
                state = UiState.Done(VerifyResult.Unreadable)
            } else {
                state = UiState.Done(verifyEngine.verify(bytes, mime ?: "image/jpeg"))
            }
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            state = UiState.Running
            val parsed = Uri.parse(initialUri)
            val (bytes, mime) = withContext(Dispatchers.IO) { readUri(ctx, parsed) }
                ?: (null to null)
            state = if (bytes == null) UiState.Done(VerifyResult.Unreadable)
            else UiState.Done(verifyEngine.verify(bytes, mime ?: "image/jpeg"))
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp)
    ) {
        Text("Verify", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            UiState.Idle -> {
                Button(onClick = { picker.launch("image/*") }) { Text("Pick an image") }
            }
            UiState.Running -> {
                Text("Verifying…", color = MaterialTheme.colorScheme.onBackground)
            }
            is UiState.Done -> ResultCard(s.result)
        }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private sealed class UiState {
    data object Idle : UiState()
    data object Running : UiState()
    data class Done(val result: VerifyResult) : UiState()
}

@Composable
private fun ResultCard(result: VerifyResult) {
    val (title, color, body) = when (result) {
        is VerifyResult.VerifiedOriginal -> Triple(
            "VERIFIED ORIGINAL", SealVerifiedColor,
            "Captured at ${result.manifest.capturedAt}\n" +
                "Device: ${result.manifest.sensor.model}\n" +
                "Detection: ${result.manifest.detection.label} (${result.manifest.detection.backend})\n" +
                "Signed by key ${result.manifest.keyId.take(16)}…"
        )
        is VerifyResult.VerifiedButModified -> Triple(
            "VERIFIED BUT MODIFIED", SealModifiedColor,
            "Signature valid, but content has been altered since capture.\n" +
                "Expected hash: ${result.expectedContentHash.take(16)}…\n" +
                "Actual hash:   ${result.actualContentHash.take(16)}…"
        )
        is VerifyResult.BrokenSeal -> Triple(
            "BROKEN SEAL", SealBrokenColor,
            "Reason: ${result.reason.code} — ${result.reason.message}"
        )
        is VerifyResult.NoProvenance -> Triple(
            "NO PROVENANCE", SealNoneColor,
            "No Pramāṇa seal found. Live detection says ${result.verdict.label} (${(result.verdict.confidence * 100).toInt()}%)."
        )
        VerifyResult.Unreadable -> Triple(
            "UNREADABLE", SealNoneColor,
            "Cannot read provenance metadata. The file may be corrupt or in an unsupported format."
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, color = color, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun readUri(ctx: android.content.Context, uri: Uri): Pair<ByteArray, String?>? {
    return try {
        val stream = ctx.contentResolver.openInputStream(uri) ?: return null
        val bytes = stream.use { it.readBytes() }
        val mime = ctx.contentResolver.getType(uri)
        bytes to mime
    } catch (t: Throwable) {
        null
    }
}
