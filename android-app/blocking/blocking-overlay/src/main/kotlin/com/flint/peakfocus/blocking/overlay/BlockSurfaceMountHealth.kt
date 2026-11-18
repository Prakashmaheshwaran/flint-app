package com.flint.peakfocus.blocking.overlay

/**
 * The latest *new* block-surface mount outcome in this process (R-30). Display/health only:
 * it never grants, denies or delays a block, and a failure never suppresses the next mount
 * attempt. Both coordinators (Path A service, Path B singleton) share it, so one path's policy
 * tick cannot erase the other's failure; only one path mounts at a time (Path B stands by while
 * Path A is connected), so "latest outcome" is the protection actually reaching the user.
 *
 * Process memory, exactly like [ProtectionStatusHub]: a restart begins at [Outcome.CLEAR]
 * ("no failed mount observed"), which is not evidence that a mount will succeed. A failure is
 * sticky until a primary surface (accessibility or SYSTEM_ALERT_WINDOW overlay) attaches.
 */
internal class BlockSurfaceMountPublisher {
    enum class Outcome(val reason: String?) {
        CLEAR(null),
        /** Overlay could not attach; the best-effort full-screen Activity launch was requested. */
        FALLBACK_ONLY(ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY),
        /** No surface was delivered: attach failed and no fallback exists or it failed to launch. */
        FAILED(ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED),
    }

    @Volatile var latest: Outcome = Outcome.CLEAR
        private set

    /** True when the outcome changed, so health should be republished. */
    @Synchronized fun record(outcome: Outcome): Boolean = (latest != outcome).also { latest = outcome }

    internal fun resetForTests() { latest = Outcome.CLEAR }
}

internal object BlockSurfaceMountHealth { val publisher = BlockSurfaceMountPublisher() }
