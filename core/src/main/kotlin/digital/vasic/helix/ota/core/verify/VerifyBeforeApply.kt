/*
 * Helix OTA — verify-before-apply DECISION logic (safety-critical, §8 integration guide).
 * Pure function: given the downloaded bytes' SHA-256, the expected SHA-256, and whether
 * the detached signature validated, decide whether to Apply or Reject (with a reason).
 *
 * The agent MUST verify the artifact BEFORE handing it to update_engine (ADR-0002 §4.1).
 * A failed verify NEVER reaches update_engine. This module contains NO crypto itself —
 * the SHA-256 and signature-valid inputs are computed upstream by Security-KMP.
 */
package digital.vasic.helix.ota.core.verify

/** Reason an artifact was rejected before apply. */
enum class RejectReason {
    /** The provided actual/expected SHA-256 strings were malformed (empty/blank). */
    MALFORMED_DIGEST,
    /** Computed SHA-256 of the downloaded bytes != expected SHA-256 from the manifest. */
    HASH_MISMATCH,
    /** Detached signature over the artifact did not validate against the pinned key. */
    SIGNATURE_INVALID,
}

/** Outcome of the verify-before-apply decision. */
sealed interface Decision {
    /** Fully verified — safe to hand the local file to update_engine. */
    data object Apply : Decision

    /** Rejected — abort apply, delete artifact, report VERIFY_FAILED. */
    data class Reject(val reason: RejectReason) : Decision
}

object VerifyBeforeApply {

    /**
     * Decide whether a downloaded artifact may be applied.
     *
     * Ordering is deliberate and load-bearing (defense in depth):
     *   1. Reject malformed digest inputs (blank actual/expected).
     *   2. Reject on hash mismatch (integrity gate).
     *   3. Reject if the signature did not validate (authenticity gate).
     *   4. Only then Apply.
     *
     * Hash comparison is case-insensitive on the hex string and trims surrounding
     * whitespace so that "9F86..." and "9f86..." are treated as equal.
     *
     * @param actualSha256   hex SHA-256 computed over the downloaded bytes (by Security-KMP).
     * @param expectedSha256 hex SHA-256 from the control-plane manifest.
     * @param signatureValid whether the detached signature validated (by Security-KMP).
     */
    fun decide(
        actualSha256: String,
        expectedSha256: String,
        signatureValid: Boolean,
    ): Decision {
        val actual = actualSha256.trim()
        val expected = expectedSha256.trim()

        if (actual.isEmpty() || expected.isEmpty()) {
            return Decision.Reject(RejectReason.MALFORMED_DIGEST)
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            return Decision.Reject(RejectReason.HASH_MISMATCH)
        }
        if (!signatureValid) {
            return Decision.Reject(RejectReason.SIGNATURE_INVALID)
        }
        return Decision.Apply
    }
}
