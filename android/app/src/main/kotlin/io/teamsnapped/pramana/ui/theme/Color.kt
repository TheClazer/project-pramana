package io.teamsnapped.pramana.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Pramāṇa color palette. Three verdict colors are the load-bearing semantic
 * tokens — they must remain distinguishable for colorblind users (see bible
 * Section 10 — accessibility). Each verdict also has an associated glyph in
 * VerdictOverlay so color is never the only signal.
 */

val PramanaBackground       = Color(0xFF0B0F14)
val PramanaSurface          = Color(0xFF131A22)
val PramanaSurfaceVariant   = Color(0xFF1C2531)
val PramanaOnBackground     = Color(0xFFEDF1F7)
val PramanaOnSurface        = Color(0xFFD9DEE5)
val PramanaOnSurfaceVariant = Color(0xFF9AA3B0)
val PramanaPrimary          = Color(0xFF7DD3FC)   // capture button, accents
val PramanaOnPrimary        = Color(0xFF0B0F14)
val PramanaOutline          = Color(0xFF2A3441)

// Verdict semantic colors
val VerdictGenuine    = Color(0xFF22C55E)   // green
val VerdictSuspicious = Color(0xFFF59E0B)   // amber
val VerdictFake       = Color(0xFFEF4444)   // red
val VerdictUnknown    = Color(0xFF6B7280)   // neutral grey

// Verify states
val SealVerifiedColor = VerdictGenuine
val SealModifiedColor = VerdictSuspicious
val SealBrokenColor   = VerdictFake
val SealNoneColor     = VerdictUnknown
