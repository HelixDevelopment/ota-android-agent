package digital.vasic.helix.ota.core.delta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaApplyDecisionTest {

    private val curVersion = "1.4.0"
    private val curSha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun offer(
        baseVersion: String = curVersion,
        baseSha256: String = curSha,
        deltaUrl: String = "https://ota.example/delta/1.4.0_to_1.5.0.bin",
        deltaSize: Long = 4_096L,
    ) = DeltaOffer(baseVersion, baseSha256, deltaUrl, deltaSize)

    @Test
    fun useDelta_whenBaseVersionAndHashMatch() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer())
        assertEquals(DeltaDecision(ApplyChoice.USE_DELTA, DeltaReason.BASE_MATCHES), d)
    }

    @Test
    fun fullPayload_whenBaseVersionMismatch() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(baseVersion = "1.3.0"))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.BASE_MISMATCH), d)
    }

    @Test
    fun fullPayload_whenBaseHashMismatch() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(baseSha256 = "deadbeef"))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.BASE_MISMATCH), d)
    }

    @Test
    fun fullPayload_whenNoDeltaOffered() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer = null)
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.NO_DELTA_OFFERED), d)
    }

    @Test
    fun fullPayload_whenDeltaMalformed_blankUrl() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(deltaUrl = "   "))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED), d)
    }

    @Test
    fun fullPayload_whenDeltaMalformed_nonPositiveSize() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(deltaSize = 0L))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED), d)
    }

    @Test
    fun fullPayload_whenDeltaMalformed_blankBaseFields() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(baseVersion = "", baseSha256 = ""))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED), d)
    }

    @Test
    fun fullPayload_whenDeviceStateBlank() {
        // A delta is well-formed but we cannot know the device's base — never risk it.
        val d = DeltaApplyDecision.decide(currentVersion = "  ", currentSha256 = curSha, offer = offer())
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED), d)
    }

    @Test
    fun baseHashComparison_isCaseInsensitiveAndTrimmed() {
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(baseSha256 = "  ${curSha.uppercase()}  "))
        assertEquals(DeltaDecision(ApplyChoice.USE_DELTA, DeltaReason.BASE_MATCHES), d)
    }

    @Test
    fun ordering_malformedTakesPrecedenceOverMismatch() {
        // Blank url AND a mismatched base version: MALFORMED is reported first.
        val d = DeltaApplyDecision.decide(curVersion, curSha, offer(baseVersion = "9.9.9", deltaUrl = ""))
        assertEquals(DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED), d)
    }

    @Test
    fun mutationImmunity_baseMatchGateIsLoadBearing() {
        // A matching base USES the delta; any mismatch FALLS BACK. A mutation that
        // inverted the base-match check would flip one of these PASS->FAIL.
        assertTrue(DeltaApplyDecision.decide(curVersion, curSha, offer()).choice == ApplyChoice.USE_DELTA)
        assertTrue(
            DeltaApplyDecision.decide(curVersion, curSha, offer(baseVersion = "1.3.0")).choice
                == ApplyChoice.FULL_PAYLOAD,
        )
    }
}
