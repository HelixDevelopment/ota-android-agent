/*
 * Helix OTA — agent ports (androidMain side). These are the thin seams over the reused
 * KMP submodules (Auth-KMP, Security-KMP, Storage-KMP, Config-KMP) and the control plane.
 * They are interfaces so the worker stays decoupled and unit-testable; real impls live
 * behind them (conceptually backed by the catalogue submodules — not added as unresolved deps).
 */
package digital.vasic.helix.ota.agent.poll

import digital.vasic.helix.ota.agent.apply.VerifiedPackage
import digital.vasic.helix.ota.core.poll.DownloadOutcome
import digital.vasic.helix.ota.core.poll.PollOutcome
import digital.vasic.helix.ota.core.protocol.TelemetryEvent
import digital.vasic.helix.ota.core.protocol.UpdateAvailable

/** Result of a poll request (maps onto the :core PollOutcome). */
sealed interface PollResult {
    data object NoUpdate : PollResult
    data class Update(val assignment: UpdateAvailable) : PollResult
    data object TransientError : PollResult

    fun toOutcome(): PollOutcome = when (this) {
        is NoUpdate -> PollOutcome.NoUpdate
        is Update -> PollOutcome.UpdateAssigned
        is TransientError -> PollOutcome.TransientError
    }
}

/** REST /api/v1 client (conceptually over the http3 transport submodule). */
interface ControlPlaneClient {
    /** GET /api/v1/client/update -> 200 manifest | 204 no update | transient error. */
    suspend fun pollForUpdate(): PollResult
}

/** Local download to /data/ota_package (conceptually over Storage-KMP). */
interface Downloader {
    /** Returns DownloadOutcome.Downloaded with the local artifact, or Failed. */
    suspend fun downloadToLocal(assignment: UpdateAvailable): DownloadResult
    fun delete(localPath: String)
}

data class DownloadResult(
    val outcome: DownloadOutcome,
    val localPath: String?,
)

/**
 * Verify-before-apply (conceptually over Security-KMP for SHA-256 + signature, plus a
 * ZIP inspector for payload props/offsets). Returns a [VerifiedPackage] only when the
 * :core VerifyBeforeApply decision is Apply.
 */
interface Verifier {
    suspend fun verify(localPath: String, assignment: UpdateAvailable): VerifyResult
}

sealed interface VerifyResult {
    data class Ok(val pkg: VerifiedPackage) : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/** Telemetry sink (conceptually over ota-telemetry-schema codecs, posted over REST). */
interface Telemetry {
    fun report(event: TelemetryEvent)
    fun reportRaw(stateName: String)
}
