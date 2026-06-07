/*
 * Helix OTA — PollOutcome state machine (integration guide §6, §11).
 * Models one poll->download->verify->apply cycle as a deterministic, pure state machine
 * so the worker logic (androidMain OtaPollWorker) is itself thin and side-effect-only.
 *
 * The terminal states map onto WorkManager's Result (success/retry/failure) — see
 * [PollState.toWorkResult] in the :android module. Transient errors -> Retry (backoff);
 * a failed verify -> Failure (poisoned artifact never reaches update_engine).
 */
package digital.vasic.helix.ota.core.poll

import digital.vasic.helix.ota.core.verify.Decision
import digital.vasic.helix.ota.core.verify.RejectReason

/** The outcome of a single poll request to the control plane. */
sealed interface PollOutcome {
    /** 204 No Content — device already up to date. */
    data object NoUpdate : PollOutcome
    /** 200 — an update is assigned. */
    data object UpdateAssigned : PollOutcome
    /** Transient failure (network/5xx) — should be retried with backoff. */
    data object TransientError : PollOutcome
}

/** Outcome of the download step. */
sealed interface DownloadOutcome {
    data object Downloaded : DownloadOutcome
    /** Transient/recoverable download failure — retry. */
    data object Failed : DownloadOutcome
}

/**
 * States of one poll cycle. [Success], [Retry], and [Failed] are terminal.
 */
sealed interface PollState {
    data object Idle : PollState
    data object Polled : PollState
    data object Assigned : PollState
    data object Downloading : PollState
    data object Downloaded : PollState
    data object Verified : PollState
    data object Applying : PollState

    /** Terminal: nothing to do or apply launched (cycle done, no retry). */
    data object Success : PollState
    /** Terminal: transient failure; WorkManager should retry with backoff. */
    data object Retry : PollState
    /** Terminal: hard failure (e.g. verify rejected); do NOT retry the same artifact. */
    data class Failed(val reason: RejectReason?) : PollState

    val isTerminal: Boolean
        get() = this is Success || this is Retry || this is Failed
}

/**
 * Pure transition function for the poll cycle. Every method returns the NEXT state;
 * callers (the worker) perform the side effects (network/IO/apply) between transitions.
 *
 * Illegal transitions throw [IllegalStateException] so a wiring bug fails loudly
 * rather than silently skipping the verify gate.
 */
object PollStateMachine {

    fun onPoll(state: PollState, outcome: PollOutcome): PollState {
        check(state is PollState.Idle || state is PollState.Polled) {
            "onPoll only valid from Idle/Polled, was $state"
        }
        return when (outcome) {
            PollOutcome.NoUpdate -> PollState.Success
            PollOutcome.UpdateAssigned -> PollState.Assigned
            PollOutcome.TransientError -> PollState.Retry
        }
    }

    fun onDownload(state: PollState, outcome: DownloadOutcome): PollState {
        check(state is PollState.Assigned) { "onDownload only valid from Assigned, was $state" }
        return when (outcome) {
            DownloadOutcome.Downloaded -> PollState.Downloaded
            DownloadOutcome.Failed -> PollState.Retry
        }
    }

    /**
     * Apply the verify-before-apply decision. A [Decision.Reject] is a terminal hard
     * failure (the artifact is poisoned/wrong and must never reach update_engine);
     * a [Decision.Apply] moves to [PollState.Verified].
     */
    fun onVerify(state: PollState, decision: Decision): PollState {
        check(state is PollState.Downloaded) { "onVerify only valid from Downloaded, was $state" }
        return when (decision) {
            is Decision.Apply -> PollState.Verified
            is Decision.Reject -> PollState.Failed(decision.reason)
        }
    }

    fun onApplyStart(state: PollState): PollState {
        check(state is PollState.Verified) { "onApplyStart only valid from Verified, was $state" }
        return PollState.Applying
    }

    /**
     * Terminal result of the apply hand-off. [applyThrew] => transient -> Retry;
     * otherwise the apply was launched successfully (reboot may tear down the process).
     */
    fun onApplyResult(state: PollState, applyThrew: Boolean): PollState {
        check(state is PollState.Applying) { "onApplyResult only valid from Applying, was $state" }
        return if (applyThrew) PollState.Retry else PollState.Success
    }
}
