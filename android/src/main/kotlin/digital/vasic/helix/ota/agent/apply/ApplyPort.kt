/*
 * Helix OTA — ApplyPort: the decoupling boundary between the agent and the OS apply path.
 *
 * The agent does NOT hard-depend on the `ota-update-engine-bridge` artifact (§11.4.28
 * decoupling). It depends only on this small interface; a bridge implementation is injected
 * at runtime. This keeps the agent buildable/testable without the @SystemApi bridge and lets
 * the bridge evolve independently.
 */
package digital.vasic.helix.ota.agent.apply

import digital.vasic.helix.ota.core.protocol.PayloadProperties

/** A fully-verified, on-disk artifact ready to hand to update_engine. */
data class VerifiedPackage(
    val localPath: String,   // e.g. /data/ota_package/ota_update.zip (already verified)
    val payloadOffset: Long, // offset of payload.bin within the outer ZIP
    val payloadSize: Long,   // size of payload.bin
    val props: PayloadProperties,
)

/** Result of launching apply. */
sealed interface ApplyResult {
    /** Apply launched; update_engine will stage the slot. A reboot follows out-of-band. */
    data object Launched : ApplyResult
    /** Apply could not be launched (transient) — caller should retry with backoff. */
    data class Failed(val cause: Throwable) : ApplyResult
}

/**
 * The ONLY OS-apply path the agent uses. Implementations (e.g. the update-engine bridge)
 * call `applyPayload(file://…, offset, size, props)` on the VERIFIED local file. The port
 * never re-verifies trust and never disables AVB/dm-verity, writes slot flags, or calls
 * markBootSuccessful (android-avb-rollback §10).
 */
interface ApplyPort {
    /** Hand a verified package to the OS apply path. Must NOT be called for an unverified file. */
    fun applyVerified(pkg: VerifiedPackage): ApplyResult
}
