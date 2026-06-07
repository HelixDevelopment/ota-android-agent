/*
 * Helix OTA — WorkManager worker running one poll->download->verify->apply cycle.
 * Transient failures use WorkManager backoff (Result.retry); the agent never busy-loops.
 * A failed verify -> Result.failure (the poisoned/wrong artifact NEVER reaches update_engine).
 *
 * The worker is a THIN driver over the pure :core PollStateMachine: it performs the side
 * effects (network/IO/apply) between transitions and maps the terminal state onto a
 * WorkManager Result. All ports are injected via [AgentDependencies] so the worker stays
 * decoupled from the bridge artifact (§11.4.28) and is unit-testable on the JVM.
 */
package digital.vasic.helix.ota.agent.poll

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import digital.vasic.helix.ota.agent.apply.ApplyPort
import digital.vasic.helix.ota.agent.apply.ApplyResult
import digital.vasic.helix.ota.core.poll.DownloadOutcome
import digital.vasic.helix.ota.core.poll.PollState
import digital.vasic.helix.ota.core.poll.PollStateMachine
import digital.vasic.helix.ota.core.protocol.TelemetryEvent

/** All ports the worker needs, injected so the worker is decoupled and testable. */
data class AgentDependencies(
    val client: ControlPlaneClient,
    val downloader: Downloader,
    val verifier: Verifier,
    val applyPort: ApplyPort,
    val telemetry: Telemetry,
)

class OtaPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deps = provider?.invoke(applicationContext)
            ?: return Result.failure() // no dependency graph wired
        return runCycle(deps).toWorkResult()
    }

    private fun PollState.toWorkResult(): Result = when (this) {
        is PollState.Success -> Result.success()
        is PollState.Retry -> Result.retry()
        is PollState.Failed -> Result.failure()
        else -> Result.failure() // non-terminal should be impossible at the end of a cycle
    }

    companion object {
        /** Injected dependency provider (set at app start). Keeps the worker bridge-agnostic. */
        @Volatile
        var provider: ((Context) -> AgentDependencies)? = null

        /**
         * Pure-ish cycle driver, extracted so it can be unit-tested on the JVM with fakes.
         * Returns the terminal [PollState].
         */
        suspend fun runCycle(deps: AgentDependencies): PollState {
            deps.telemetry.reportRaw("POLLED")

            // 1) poll
            val pollResult = deps.client.pollForUpdate()
            var state = PollStateMachine.onPoll(PollState.Idle, pollResult.toOutcome())
            if (state.isTerminal) return state // NoUpdate -> Success, TransientError -> Retry
            val assignment = (pollResult as PollResult.Update).assignment
            deps.telemetry.reportRaw("UPDATE_ASSIGNED")

            // 2) download
            deps.telemetry.report(TelemetryEvent.DOWNLOAD_STARTED)
            val download = deps.downloader.downloadToLocal(assignment)
            state = PollStateMachine.onDownload(state, download.outcome)
            if (state.isTerminal) return state // Failed -> Retry
            val localPath = download.localPath!!

            // 3) verify-before-apply (safety-critical gate)
            deps.telemetry.report(TelemetryEvent.VERIFYING)
            val verify = deps.verifier.verify(localPath, assignment)
            state = when (verify) {
                is VerifyResult.Ok ->
                    PollStateMachine.onVerify(state, digital.vasic.helix.ota.core.verify.Decision.Apply)
                is VerifyResult.Rejected ->
                    PollStateMachine.onVerify(
                        state,
                        digital.vasic.helix.ota.core.verify.Decision.Reject(
                            digital.vasic.helix.ota.core.verify.RejectReason.HASH_MISMATCH,
                        ),
                    )
            }
            if (state is PollState.Failed) {
                deps.telemetry.report(TelemetryEvent.FAILURE)
                deps.downloader.delete(localPath) // poisoned artifact never reaches update_engine
                return state
            }

            // 4) apply the VERIFIED package via the decoupled ApplyPort
            state = PollStateMachine.onApplyStart(state)
            deps.telemetry.report(TelemetryEvent.INSTALLING)
            val pkg = (verify as VerifyResult.Ok).pkg
            val applyThrew = when (deps.applyPort.applyVerified(pkg)) {
                is ApplyResult.Launched -> false
                is ApplyResult.Failed -> true
            }
            state = PollStateMachine.onApplyResult(state, applyThrew)
            if (state is PollState.Success) deps.telemetry.report(TelemetryEvent.INSTALLED)
            return state
        }
    }
}
