package io.teamsnapped.pramana.mocks

import io.teamsnapped.pramana.api.RppgStream
import kotlin.math.sin

/**
 * Synthesizes a "healthy" pulse signal — the score oscillates around 0.75
 * with a small wobble, mimicking a strong rPPG-positive case.
 *
 * Returns null for the first 30 pushes (simulating buffer-fill time) so the
 * fusion layer's "rppg-unavailable" path is exercised.
 */
class MockRppgStream : RppgStream {
    private var frameCount = 0

    override fun pushFrame(rgb: ByteArray, width: Int, height: Int, timestampNs: Long) {
        frameCount++
    }

    override fun latestScore(): Float? {
        if (frameCount < 30) return null
        val phase = frameCount * 0.05
        return (0.72f + (0.08f * sin(phase)).toFloat()).coerceIn(0f, 1f)
    }

    override fun reset() { frameCount = 0 }
}
