package io.teamsnapped.pramana.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Pramāṇa is dark-first by design — the camera preview lives more comfortably
 * on a dark surface and the verdict colors pop better. We don't ship a light
 * scheme yet; if [isSystemInDarkTheme] is false we still use the dark scheme.
 */
private val PramanaDarkColors = darkColorScheme(
    background          = PramanaBackground,
    surface             = PramanaSurface,
    surfaceVariant      = PramanaSurfaceVariant,
    onBackground        = PramanaOnBackground,
    onSurface           = PramanaOnSurface,
    onSurfaceVariant    = PramanaOnSurfaceVariant,
    primary             = PramanaPrimary,
    onPrimary           = PramanaOnPrimary,
    outline             = PramanaOutline
)

@Composable
fun PramanaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PramanaDarkColors,
        typography = PramanaTypography,
        content = content
    )
}
