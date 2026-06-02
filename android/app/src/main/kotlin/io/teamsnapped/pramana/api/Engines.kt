package io.teamsnapped.pramana.api

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.SharedFlow

/**
 * The four module contracts that the app code depends on.
 *
 * Each interface has at least one real implementation (in its owner module)
 * and a mock (`mocks/`) for parallel development. The app must run
 * end-to-end with any subset of mocks substituted — bible Section 5,
 * Pattern 1.
 *
 * Do NOT add convenience methods here without coordinating across all
 * engineers — every method added is something every implementation has to
 * support, and any implementation breaking the contract breaks every
 * downstream module.
 */

// ---------------------------------------------------------------------------
//  DetectionEngine — Engineer A
// ---------------------------------------------------------------------------

/**
 * The deepfake detection pipeline. The implementation may run anywhere
 * (Hexagon NPU via QNN delegate / GPU delegate / CPU XNNPACK / mock) — the
 * contract is the same.
 *
 * Bible Section 6.
 */
interface DetectionEngine {

    /**
     * Analyze a single frame. Suspending — callers await this so we never
     * stack invocations. CameraX's `STRATEGY_KEEP_ONLY_LATEST` drops older
     * frames if the engine is slow.
     *
     * Implementations MUST:
     *  - return within a reasonable time bound (<35ms target for NPU);
     *  - never throw — wrap engine errors in a SUSPICIOUS verdict with
     *    `backend = "ERR"` so the camera loop keeps running;
     *  - be safe to call from any coroutine context.
     */
    suspend fun analyze(frame: FrameInput): Verdict

    /** True once weights are loaded and a warm-up inference has succeeded. */
    fun isReady(): Boolean

    /** "NPU" | "GPU" | "CPU" | "MOCK" | "ERR". Surfaced in the UI HUD. */
    fun backend(): String

    /**
     * Re-initialize pinned to a single backend ("AUTO"|"NPU"|"GPU"|"CPU") and
     * return the tier ACTUALLY reached. Used for the live NPU-vs-CPU latency A/B
     * demo (bible: the strongest single proof the NPU is real). Default no-op for
     * mocks — they just report their current backend.
     */
    fun forceBackend(mode: String): String = backend()

    /** Release native resources. Idempotent. */
    fun close()
}

// ---------------------------------------------------------------------------
//  SealEngine — Engineer C
// ---------------------------------------------------------------------------

/** Output of [SealEngine.seal]. */
data class SealedFile(
    /** File bytes with both EXIF manifest and DCT pixel watermark embedded. */
    val bytes:    ByteArray,
    /** The manifest we just embedded — useful for the UI's "just sealed!" card. */
    val manifest: Manifest,
    /** True iff Keystore signing succeeded. False means we wrote an unsigned manifest
     *  (very rare — only happens if Keystore ladder fully failed). */
    val sigOk:    Boolean
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Seals a single captured file (image for v1.1 — video stretch goal).
 *
 * Bible Section 7. Implementation:
 *  1. SHA-256 the pixel bytes.
 *  2. Build manifest (sensor + detection + content hash).
 *  3. JCS-canonicalize, ECDSA-sign via Keystore.
 *  4. Embed signed manifest in EXIF UserComment.
 *  5. Embed DCT pixel watermark carrying the manifest fingerprint.
 *  6. Return the resulting bytes (caller writes to MediaStore).
 */
interface SealEngine {
    suspend fun seal(
        bytes:       ByteArray,
        captureMeta: CaptureMeta,
        detection:   Verdict
    ): SealedFile

    /** True iff Keystore is provisioned (first-run completed). */
    fun isReady(): Boolean

    /** Base64 public key matching the device's signing key. */
    fun publicKeyB64(): String
}

// ---------------------------------------------------------------------------
//  VerifyEngine — Engineer C
// ---------------------------------------------------------------------------

/**
 * Verifies any file (image or video) and returns one of the five outcomes
 * from [VerifyResult]. Falls back to live detection when no provenance is
 * present.
 *
 * Bible Section 8.
 */
interface VerifyEngine {
    suspend fun verify(bytes: ByteArray, mimeType: String): VerifyResult
}

// ---------------------------------------------------------------------------
//  CameraEngine — Engineer B
// ---------------------------------------------------------------------------

/**
 * Camera lifecycle + ImageAnalysis pipeline.
 *
 * Bible Section 9. Implementation uses CameraX `ImageAnalysis` with
 * `YUV_420_888` output and pre-allocated ByteBuffers. The verdict bus
 * is a SharedFlow consumers can collect from.
 *
 * The mock implementation produces synthetic verdicts at ~30 Hz from a
 * scripted sequence (bible Appendix A example) so the UI is testable
 * without a real camera.
 */
interface CameraEngine {
    /** Bind to an Android lifecycle and start the preview + analyzer pipeline. */
    fun start(lifecycleOwner: LifecycleOwner)

    /** Tear down. Idempotent. */
    fun stop()

    /**
     * Attach the camera preview surface (an `androidx.camera.view.PreviewView`
     * created by Compose AndroidView). Pass it BEFORE calling [start] so the
     * Preview use case binds with a real surface; otherwise the screen stays
     * black. Safe to call again after [start] (e.g. on rotation) to hot-swap
     * the surface without restarting analysis. Pass `null` to detach.
     *
     * Default no-op — mocks don't need to implement it.
     */
    fun attachPreview(view: Any?) {}

    /**
     * Fire-and-forget capture. The result callback receives a sealed file.
     * `null` indicates capture failure (file system error, etc.).
     */
    fun captureStill(onResult: (SealedFile?) -> Unit)

    /** Stream of verdicts at ~30 Hz. Replays the latest to new collectors. */
    val verdictBus: SharedFlow<Verdict>

    /** Current backend label, surfaced to the UI HUD. */
    fun backend(): String
}

// ---------------------------------------------------------------------------
//  RppgStream — Engineer A (used internally by DetectionEngine fusion)
// ---------------------------------------------------------------------------

/**
 * The CPU-side cardiac liveness pipeline. Consumes raw RGB frames, runs
 * MediaPipe Face Mesh + POS + FFT, emits a single liveness score per
 * 30-frame sliding window.
 *
 * Bible Section 6 — "rPPG cardiac liveness".
 *
 * Returned score semantics:
 *  - `null` when unavailable (no face / low landmark confidence / not
 *    enough frames in the buffer yet). The fusion layer falls back to
 *    classifier-only when this is null.
 *  - `[0.0, 1.0]` otherwise; higher = stronger pulse signal in the
 *    physiological band (0.75–3 Hz).
 */
interface RppgStream {
    /** Push a new frame. Cheap — does buffer rotation but defers FFT to [latestScore]. */
    fun pushFrame(rgb: ByteArray, width: Int, height: Int, timestampNs: Long)

    /** Read the most recent liveness score, or null if not yet computable. */
    fun latestScore(): Float?

    /** Reset the sliding buffer (e.g. when the face is lost). */
    fun reset()
}
