/*
 * Helix OTA — device-side delta-apply DECISION logic (pure, §4 integration guide).
 * Pure function: given the device's current version and an available update that MAY
 * carry a base->target delta artifact alongside the full payload, decide whether to
 * fetch+apply the small delta or fall back to the full payload.
 *
 * USE_DELTA is chosen ONLY when the offer's base exactly matches the device's current
 * state (version + base SHA-256) AND the offer is well-formed (non-blank url, positive
 * size). Anything else falls back to the full payload — never an unsafe delta.
 *
 * This module contains NO crypto and NO IO. The base/target SHA-256 inputs are computed
 * upstream (by Security-KMP); the actual fetch/apply is performed by the :android wiring.
 * The integrity/authenticity gate over the resulting bytes remains [VerifyBeforeApply]'s
 * job — this module decides ONLY which artifact (delta vs full) to pull.
 */
package digital.vasic.helix.ota.core.delta

/**
 * Device-side view of a delta offer carried alongside a full payload (UpdateAvailable).
 * Marked clearly as the device-side projection of the server's delta metadata — it is
 * NOT a wire DTO (the protocol DTOs in [digital.vasic.helix.ota.core.protocol] model the
 * full-payload contract; this is the optional delta sidecar the device reasons about).
 *
 * @param baseVersion  the version the delta upgrades FROM (must equal the device's
 *                     current version for the delta to be applicable).
 * @param baseSha256   lowercase-hex SHA-256 of the base image the delta patches against
 *                     (must equal the device's current image SHA-256).
 * @param deltaUrl     URL the device would fetch the delta artifact from.
 * @param deltaSize    size in bytes of the delta artifact (must be > 0).
 */
data class DeltaOffer(
    val baseVersion: String,
    val baseSha256: String,
    val deltaUrl: String,
    val deltaSize: Long,
)

/** Which artifact the device should fetch + apply. */
enum class ApplyChoice {
    /** Fetch + apply the small base->target delta. */
    USE_DELTA,
    /** Fall back to the full payload. */
    FULL_PAYLOAD,
}

/** Why [DeltaApplyDecision] chose [ApplyChoice.FULL_PAYLOAD] (or why USE_DELTA is safe). */
enum class DeltaReason {
    /** Delta base matches the device's current version + base hash; delta is well-formed. */
    BASE_MATCHES,
    /** No delta sidecar was offered with the update. */
    NO_DELTA_OFFERED,
    /** Delta offered, but its base (version or base SHA-256) != the device's current state. */
    BASE_MISMATCH,
    /** Delta offered, but malformed (blank base/url, non-positive size, blank current state). */
    DELTA_MALFORMED,
}

/** Outcome of the delta-apply decision: the [choice] plus the [reason] that produced it. */
data class DeltaDecision(
    val choice: ApplyChoice,
    val reason: DeltaReason,
)

object DeltaApplyDecision {

    /**
     * Decide whether to apply a delta or fall back to the full payload.
     *
     * Ordering is deliberate and load-bearing:
     *   1. No offer at all -> FULL_PAYLOAD (NO_DELTA_OFFERED).
     *   2. Malformed offer or malformed device state -> FULL_PAYLOAD (DELTA_MALFORMED).
     *   3. Base (version OR base SHA-256) != device's current state -> FULL_PAYLOAD (BASE_MISMATCH).
     *   4. Only then -> USE_DELTA (BASE_MATCHES).
     *
     * Version comparison trims surrounding whitespace and is exact (case-sensitive — versions
     * are opaque identifiers). SHA-256 comparison trims and is case-insensitive on the hex
     * string so that "9F86..." and "9f86..." are treated as equal (same convention as
     * [digital.vasic.helix.ota.core.verify.VerifyBeforeApply]).
     *
     * @param currentVersion the version currently running on the device.
     * @param currentSha256  lowercase-hex SHA-256 of the device's current image.
     * @param offer          the optional delta sidecar; null when only a full payload exists.
     */
    fun decide(
        currentVersion: String,
        currentSha256: String,
        offer: DeltaOffer?,
    ): DeltaDecision {
        if (offer == null) {
            return DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.NO_DELTA_OFFERED)
        }

        val curVersion = currentVersion.trim()
        val curSha = currentSha256.trim()
        val baseVersion = offer.baseVersion.trim()
        val baseSha = offer.baseSha256.trim()
        val deltaUrl = offer.deltaUrl.trim()

        val malformed = curVersion.isEmpty() ||
            curSha.isEmpty() ||
            baseVersion.isEmpty() ||
            baseSha.isEmpty() ||
            deltaUrl.isEmpty() ||
            offer.deltaSize <= 0L
        if (malformed) {
            return DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.DELTA_MALFORMED)
        }

        val baseMatches = baseVersion == curVersion &&
            baseSha.equals(curSha, ignoreCase = true)
        if (!baseMatches) {
            return DeltaDecision(ApplyChoice.FULL_PAYLOAD, DeltaReason.BASE_MISMATCH)
        }

        return DeltaDecision(ApplyChoice.USE_DELTA, DeltaReason.BASE_MATCHES)
    }
}
