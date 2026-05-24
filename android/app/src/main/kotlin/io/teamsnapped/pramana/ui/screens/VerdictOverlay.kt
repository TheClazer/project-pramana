package io.teamsnapped.pramana.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.teamsnapped.pramana.api.Verdict
import io.teamsnapped.pramana.api.VerdictLabel
import io.teamsnapped.pramana.ui.theme.VerdictFake
import io.teamsnapped.pramana.ui.theme.VerdictGenuine
import io.teamsnapped.pramana.ui.theme.VerdictSuspicious
import io.teamsnapped.pramana.ui.theme.VerdictUnknown

/**
 * Centerpiece verdict card the operator sees while filming.
 *
 * Each verdict carries:
 *  - the colored label (GENUINE / SUSPICIOUS / FAKE),
 *  - confidence percentage,
 *  - NPU sub-score + rPPG sub-score so power users see the dual-stream signal,
 *  - backend label (NPU/GPU/CPU/MOCK).
 *
 * Bible Section 9 — verdict overlay text is the primary live signal; the
 * Grad-CAM heatmap is rendered on tap (stretch 1) but not here.
 */
@Composable
fun VerdictOverlay(verdict: Verdict?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(16.dp)) {
        if (verdict == null) {
            HollowCard(text = "Warming up…")
        } else {
            VerdictCard(verdict)
        }
    }
}

@Composable
private fun VerdictCard(v: Verdict) {
    val color = colorFor(v.label)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                v.label.name,
                color = color,
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${(v.confidence * 100).toInt()}%  confidence",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "npu=${"%.2f".format(v.npuScore)}   rppg=${rppgText(v.rppgScore)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                "backend=${v.backend}   ${v.modelId}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HollowCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

internal fun colorFor(label: VerdictLabel): Color = when (label) {
    VerdictLabel.GENUINE    -> VerdictGenuine
    VerdictLabel.SUSPICIOUS -> VerdictSuspicious
    VerdictLabel.FAKE       -> VerdictFake
}

private fun rppgText(score: Float?): String =
    if (score == null) "n/a" else "%.2f".format(score)
