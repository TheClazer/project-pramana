package io.teamsnapped.pramana.detection

import com.google.common.truth.Truth.assertThat
import io.teamsnapped.pramana.api.VerdictLabel
import org.junit.Test

/**
 * Bible §6 fusion rules:
 *   authenticity = 0.65 * (1 - npu_score) + 0.35 * rppg_score    # rPPG avail
 *   authenticity = 1.0  * (1 - npu_score)                        # else
 *   if rppg_available && rppg_score < 0.2 for > 2s:
 *       authenticity = min(authenticity, 0.35)                   # HARD VETO
 *   label = GENUINE if a>0.7, SUSPICIOUS if a>0.3, FAKE otherwise
 */
class FusionTest {

    @Test
    fun `genuine threshold low npu plus high rppg`() {
        val (label, a) = Fusion().fuse(npuScore = 0.05f, rppgScore = 0.85f, nowMs = 1_000L)
        assertThat(label).isEqualTo(VerdictLabel.GENUINE)
        assertThat(a).isGreaterThan(0.7f)
    }

    @Test
    fun `fake threshold high npu`() {
        val (label, a) = Fusion().fuse(npuScore = 0.95f, rppgScore = 0.10f, nowMs = 1_000L)
        assertThat(label).isEqualTo(VerdictLabel.FAKE)
        assertThat(a).isLessThan(0.3f)
    }

    @Test
    fun `suspicious in between`() {
        val (label, a) = Fusion().fuse(npuScore = 0.55f, rppgScore = 0.55f, nowMs = 1_000L)
        assertThat(label).isEqualTo(VerdictLabel.SUSPICIOUS)
        assertThat(a).isGreaterThan(0.3f)
        assertThat(a).isLessThan(0.7f)
    }

    @Test
    fun `rppg unavailable falls back to classifier-only`() {
        val (label, a) = Fusion().fuse(npuScore = 0.05f, rppgScore = null, nowMs = 1_000L)
        assertThat(label).isEqualTo(VerdictLabel.GENUINE)
        assertThat(a).isWithin(1e-3f).of(0.95f)
    }

    @Test
    fun `hard veto kicks in after 2 seconds of sustained low rppg`() {
        val f = Fusion()

        // First sample at t=0 establishes the start of the window.
        val (label1, _) = f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = 0L)
        // No span yet (only one sample), authenticity = 0.65*0.95 + 0.35*0.05 = 0.635.
        assertThat(label1).isEqualTo(VerdictLabel.SUSPICIOUS)

        // Exactly 2 seconds later (the hard-veto window edge). The sample at
        // t=0 is NOT pruned because the prune predicate is `tsMs < now - 2000`,
        // and 0 < 0 is false. Span = 2000 - 0 = 2000ms ≥ 2000ms → veto fires.
        val (label2, a2) = f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = 2_000L)
        assertThat(a2).isAtMost(0.35f)
        assertThat(label2).isNotEqualTo(VerdictLabel.GENUINE)
    }

    @Test
    fun `hard veto holds across 60 fps samples for 2 seconds`() {
        val f = Fusion()
        // Bootstrap a sample exactly at the window-start boundary (t = now-2000)
        // so the veto can fire after we walk forward through a stream.
        f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = 0L)
        // Walk through 2 seconds at 30 fps with consistently low rPPG.
        for (idx in 1..59) {
            f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = idx * 33L)
        }
        val (label, a) = f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = 2_000L)
        assertThat(a).isAtMost(0.35f)
        assertThat(label).isNotEqualTo(VerdictLabel.GENUINE)
    }

    @Test
    fun `transient single low sample does not veto`() {
        val f = Fusion()
        // Two seconds of high rPPG, then one low blip
        repeat(60) { idx ->
            val t = (idx + 1) * 33L
            f.fuse(npuScore = 0.05f, rppgScore = 0.85f, nowMs = t)
        }
        val (label, a) = f.fuse(npuScore = 0.05f, rppgScore = 0.05f, nowMs = 2_100L)
        // One low sample shouldn't trip the veto.
        assertThat(a).isGreaterThan(0.35f)
        assertThat(label).isAnyOf(VerdictLabel.GENUINE, VerdictLabel.SUSPICIOUS)
    }

    @Test
    fun `rppg unavailable clears history so no stale veto`() {
        val f = Fusion()
        // Build up 2s of low rPPG
        repeat(70) { idx ->
            f.fuse(npuScore = 0.5f, rppgScore = 0.05f, nowMs = idx * 33L)
        }
        // Now rPPG goes unavailable
        val (_, a) = f.fuse(npuScore = 0.05f, rppgScore = null, nowMs = 3_000L)
        // With unavailable rPPG, formula is just 1 - npu = 0.95 → GENUINE; veto cleared.
        assertThat(a).isWithin(1e-3f).of(0.95f)
    }
}
