package digital.vasic.helix.ota.core.verify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyBeforeApplyTest {

    private val good = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test
    fun apply_whenHashMatchesAndSignatureValid() {
        val d = VerifyBeforeApply.decide(good, good, signatureValid = true)
        assertEquals(Decision.Apply, d)
    }

    @Test
    fun reject_hashMismatch() {
        val d = VerifyBeforeApply.decide(good, "deadbeef", signatureValid = true)
        assertEquals(Decision.Reject(RejectReason.HASH_MISMATCH), d)
    }

    @Test
    fun reject_signatureInvalid_evenWhenHashMatches() {
        val d = VerifyBeforeApply.decide(good, good, signatureValid = false)
        assertEquals(Decision.Reject(RejectReason.SIGNATURE_INVALID), d)
    }

    @Test
    fun reject_malformed_whenActualBlank() {
        val d = VerifyBeforeApply.decide("   ", good, signatureValid = true)
        assertEquals(Decision.Reject(RejectReason.MALFORMED_DIGEST), d)
    }

    @Test
    fun reject_malformed_whenExpectedEmpty() {
        val d = VerifyBeforeApply.decide(good, "", signatureValid = true)
        assertEquals(Decision.Reject(RejectReason.MALFORMED_DIGEST), d)
    }

    @Test
    fun hashComparison_isCaseInsensitiveAndTrimmed() {
        val d = VerifyBeforeApply.decide("  ${good.uppercase()}  ", good, signatureValid = true)
        assertEquals(Decision.Apply, d)
    }

    @Test
    fun ordering_malformedTakesPrecedenceOverHashAndSignature() {
        // Blank actual must be MALFORMED, not HASH_MISMATCH, even with bad signature.
        val d = VerifyBeforeApply.decide("", "x", signatureValid = false)
        assertEquals(Decision.Reject(RejectReason.MALFORMED_DIGEST), d)
    }

    @Test
    fun ordering_hashMismatchTakesPrecedenceOverSignature() {
        // When both hash mismatches AND signature invalid, hash is reported first.
        val d = VerifyBeforeApply.decide(good, "deadbeef", signatureValid = false)
        assertEquals(Decision.Reject(RejectReason.HASH_MISMATCH), d)
    }

    @Test
    fun mutationImmunity_invertedHashCompareWouldFlipDecision() {
        // Asserts the hash gate is load-bearing: a matching pair Applies, a
        // non-matching pair Rejects. A mutation that inverts the comparison
        // would turn one of these PASS->FAIL.
        assertTrue(VerifyBeforeApply.decide(good, good, true) is Decision.Apply)
        assertTrue(VerifyBeforeApply.decide(good, "deadbeef", true) is Decision.Reject)
    }
}
