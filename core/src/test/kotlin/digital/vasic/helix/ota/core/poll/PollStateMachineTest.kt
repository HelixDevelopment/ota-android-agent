package digital.vasic.helix.ota.core.poll

import digital.vasic.helix.ota.core.verify.Decision
import digital.vasic.helix.ota.core.verify.RejectReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PollStateMachineTest {

    @Test
    fun poll_noUpdate_terminatesSuccess() {
        assertEquals(
            PollState.Success,
            PollStateMachine.onPoll(PollState.Idle, PollOutcome.NoUpdate),
        )
    }

    @Test
    fun poll_transientError_terminatesRetry() {
        assertEquals(
            PollState.Retry,
            PollStateMachine.onPoll(PollState.Idle, PollOutcome.TransientError),
        )
    }

    @Test
    fun fullHappyPath_pollToApplySuccess() {
        var s: PollState = PollState.Idle
        s = PollStateMachine.onPoll(s, PollOutcome.UpdateAssigned)
        assertEquals(PollState.Assigned, s)
        s = PollStateMachine.onDownload(s, DownloadOutcome.Downloaded)
        assertEquals(PollState.Downloaded, s)
        s = PollStateMachine.onVerify(s, Decision.Apply)
        assertEquals(PollState.Verified, s)
        s = PollStateMachine.onApplyStart(s)
        assertEquals(PollState.Applying, s)
        s = PollStateMachine.onApplyResult(s, applyThrew = false)
        assertEquals(PollState.Success, s)
        assertTrue(s.isTerminal)
    }

    @Test
    fun downloadFailure_terminatesRetry() {
        val assigned = PollStateMachine.onPoll(PollState.Idle, PollOutcome.UpdateAssigned)
        assertEquals(PollState.Retry, PollStateMachine.onDownload(assigned, DownloadOutcome.Failed))
    }

    @Test
    fun verifyReject_terminatesHardFailure_neverReachesApply() {
        val downloaded = PollState.Downloaded
        val s = PollStateMachine.onVerify(downloaded, Decision.Reject(RejectReason.HASH_MISMATCH))
        assertEquals(PollState.Failed(RejectReason.HASH_MISMATCH), s)
        assertTrue(s.isTerminal)
    }

    @Test
    fun applyThrow_terminatesRetry() {
        val applying = PollState.Applying
        assertEquals(PollState.Retry, PollStateMachine.onApplyResult(applying, applyThrew = true))
    }

    @Test
    fun illegalTransition_onVerifyFromIdle_throws() {
        assertFailsWith<IllegalStateException> {
            PollStateMachine.onVerify(PollState.Idle, Decision.Apply)
        }
    }

    @Test
    fun illegalTransition_onApplyStartFromDownloaded_throws() {
        // Apply must NOT be reachable directly from Downloaded (verify gate is mandatory).
        assertFailsWith<IllegalStateException> {
            PollStateMachine.onApplyStart(PollState.Downloaded)
        }
    }

    @Test
    fun illegalTransition_onDownloadFromIdle_throws() {
        assertFailsWith<IllegalStateException> {
            PollStateMachine.onDownload(PollState.Idle, DownloadOutcome.Downloaded)
        }
    }

    @Test
    fun terminalFlags() {
        assertTrue(PollState.Success.isTerminal)
        assertTrue(PollState.Retry.isTerminal)
        assertTrue(PollState.Failed(null).isTerminal)
        assertTrue(!PollState.Idle.isTerminal)
        assertTrue(!PollState.Verified.isTerminal)
    }

    @Test
    fun nonTerminalFlags_everyIntermediateStateIsNonTerminal() {
        // isTerminal must be false for every NON-terminal state. This both exercises the
        // intermediate PollState members (Polled/Assigned/Downloading/Downloaded/Applying)
        // and asserts the terminal predicate does not over-fire on them.
        assertTrue(!PollState.Polled.isTerminal)
        assertTrue(!PollState.Assigned.isTerminal)
        assertTrue(!PollState.Downloading.isTerminal)
        assertTrue(!PollState.Downloaded.isTerminal)
        assertTrue(!PollState.Applying.isTerminal)
    }

    @Test
    fun poll_fromPolledState_isAlsoValid() {
        // onPoll accepts Idle OR Polled (a re-poll after a prior poll). The Polled branch
        // of the `check` precondition was previously unexercised.
        assertEquals(
            PollState.Assigned,
            PollStateMachine.onPoll(PollState.Polled, PollOutcome.UpdateAssigned),
        )
        assertEquals(
            PollState.Success,
            PollStateMachine.onPoll(PollState.Polled, PollOutcome.NoUpdate),
        )
        assertEquals(
            PollState.Retry,
            PollStateMachine.onPoll(PollState.Polled, PollOutcome.TransientError),
        )
    }

    @Test
    fun illegalTransition_onPollFromAssigned_throws() {
        // onPoll is only valid from Idle/Polled — any other state must fail loudly so a
        // wiring bug never silently re-polls mid-cycle.
        assertFailsWith<IllegalStateException> {
            PollStateMachine.onPoll(PollState.Assigned, PollOutcome.NoUpdate)
        }
    }

    @Test
    fun illegalTransition_onApplyResultFromNonApplying_throws() {
        // onApplyResult is only valid from Applying — the failing `check` branch on
        // line 106 of PollStateMachine.kt was previously unexercised.
        assertFailsWith<IllegalStateException> {
            PollStateMachine.onApplyResult(PollState.Verified, applyThrew = false)
        }
    }
}
