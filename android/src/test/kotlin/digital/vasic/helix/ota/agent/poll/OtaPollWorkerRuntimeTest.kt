/*
 * Helix OTA — JVM unit tests for OtaPollWorker.runCycle.
 *
 * runCycle is a "pure-ish" suspend driver (see OtaPollWorker.kt's own doc comment): it calls
 * only injected port interfaces (ControlPlaneClient/Downloader/Verifier/ApplyPort/Telemetry)
 * and the framework-independent :core PollStateMachine — it makes NO real Android-framework
 * calls. That means it is host-JVM testable with plain fakes, no Robolectric/emulator needed;
 * this file exercises it directly via `testDebugUnitTest`.
 *
 * Covers two regressions found in adversarial audit of ota-android-agent @ be27cca:
 *
 *   1. Downloader.downloadToLocal's contract promises a non-null localPath whenever the
 *      outcome is Downloaded, but DownloadResult.localPath is typed String? — nothing in the
 *      type system enforces the pairing. The original code read it with `!!`, so a
 *      buggy/racing Downloader implementation returning Downloaded with a null path crashed
 *      the worker with an uncaught NullPointerException instead of degrading to Retry.
 *
 *   2. Every VerifyResult.Rejected used to be mapped onto a hardcoded
 *      RejectReason.HASH_MISMATCH regardless of the Verifier's actual reason, silently
 *      collapsing a security-relevant SIGNATURE_INVALID (or MALFORMED_DIGEST) rejection into
 *      a benign-looking hash-mismatch report.
 */
package digital.vasic.helix.ota.agent.poll

import digital.vasic.helix.ota.agent.apply.ApplyPort
import digital.vasic.helix.ota.agent.apply.ApplyResult
import digital.vasic.helix.ota.agent.apply.VerifiedPackage
import digital.vasic.helix.ota.core.poll.DownloadOutcome
import digital.vasic.helix.ota.core.poll.PollState
import digital.vasic.helix.ota.core.protocol.PayloadProperties
import digital.vasic.helix.ota.core.protocol.TelemetryEvent
import digital.vasic.helix.ota.core.protocol.UpdateAvailable
import digital.vasic.helix.ota.core.verify.RejectReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun sampleAssignment(): UpdateAvailable = UpdateAvailable(
    releaseId = "release-1",
    version = "1.1.0",
    url = "https://ota.example/releases/1.1.0.zip",
    offset = 0L,
    size = 4096L,
    sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    signature = "c2ln",
    payloadProperties = PayloadProperties(
        fileHashB64 = "AA==",
        fileSize = 4096L,
        metadataHashB64 = "BB==",
        metadataSize = 32L,
    ),
)

private class FakeControlPlaneClient(private val result: PollResult) : ControlPlaneClient {
    override suspend fun pollForUpdate(): PollResult = result
}

private class FakeDownloader(private val result: DownloadResult) : Downloader {
    var deletedPath: String? = null
    override suspend fun downloadToLocal(assignment: UpdateAvailable): DownloadResult = result
    override fun delete(localPath: String) {
        deletedPath = localPath
    }
}

private class FakeVerifier(private val result: VerifyResult) : Verifier {
    override suspend fun verify(localPath: String, assignment: UpdateAvailable): VerifyResult = result
}

private class FakeApplyPort(private val result: ApplyResult) : ApplyPort {
    override fun applyVerified(pkg: VerifiedPackage): ApplyResult = result
}

private class FakeTelemetry : Telemetry {
    val reported = mutableListOf<String>()
    override fun report(event: TelemetryEvent) {
        reported += event.wire
    }

    override fun reportRaw(stateName: String) {
        reported += stateName
    }
}

class OtaPollWorkerRuntimeTest {

    /**
     * REGRESSION (was `download.localPath!!`): a Downloader that reports Downloaded but
     * returns a null localPath must never crash the cycle with an uncaught NPE. The worker
     * must treat the broken invariant as transient and let WorkManager retry with backoff.
     */
    @Test
    fun `download reports Downloaded with a null localPath does not crash and retries`() = runBlocking {
        val downloader = FakeDownloader(DownloadResult(outcome = DownloadOutcome.Downloaded, localPath = null))
        val deps = AgentDependencies(
            client = FakeControlPlaneClient(PollResult.Update(sampleAssignment())),
            downloader = downloader,
            verifier = FakeVerifier(
                VerifyResult.Ok(
                    VerifiedPackage(
                        localPath = "/data/ota_package/unused.zip",
                        payloadOffset = 0L,
                        payloadSize = 1L,
                        props = sampleAssignment().payloadProperties,
                    ),
                ),
            ),
            applyPort = FakeApplyPort(ApplyResult.Launched),
            telemetry = FakeTelemetry(),
        )

        val state = OtaPollWorker.runCycle(deps)

        assertEquals(PollState.Retry, state)
        // The broken-invariant path never had a real local file to clean up.
        assertNull(downloader.deletedPath)
    }

    /** Baseline: a well-formed Downloaded result with a real path still completes normally. */
    @Test
    fun `download with a real localPath completes the happy path`() = runBlocking {
        val deps = AgentDependencies(
            client = FakeControlPlaneClient(PollResult.Update(sampleAssignment())),
            downloader = FakeDownloader(
                DownloadResult(outcome = DownloadOutcome.Downloaded, localPath = "/data/ota_package/ota_update.zip"),
            ),
            verifier = FakeVerifier(
                VerifyResult.Ok(
                    VerifiedPackage(
                        localPath = "/data/ota_package/ota_update.zip",
                        payloadOffset = 0L,
                        payloadSize = 4096L,
                        props = sampleAssignment().payloadProperties,
                    ),
                ),
            ),
            applyPort = FakeApplyPort(ApplyResult.Launched),
            telemetry = FakeTelemetry(),
        )

        val state = OtaPollWorker.runCycle(deps)

        assertEquals(PollState.Success, state)
    }

    /**
     * REGRESSION (was a hardcoded RejectReason.HASH_MISMATCH for every VerifyResult.Rejected):
     * the worker must propagate the Verifier's ACTUAL reason. A SIGNATURE_INVALID rejection
     * (a security-relevant authenticity failure) must never be reported as HASH_MISMATCH.
     */
    @Test
    fun `verify rejection propagates SIGNATURE_INVALID, not a hardcoded HASH_MISMATCH`() = runBlocking {
        val downloader = FakeDownloader(
            DownloadResult(outcome = DownloadOutcome.Downloaded, localPath = "/data/ota_package/ota_update.zip"),
        )
        val deps = AgentDependencies(
            client = FakeControlPlaneClient(PollResult.Update(sampleAssignment())),
            downloader = downloader,
            verifier = FakeVerifier(VerifyResult.Rejected(RejectReason.SIGNATURE_INVALID)),
            applyPort = FakeApplyPort(ApplyResult.Launched),
            telemetry = FakeTelemetry(),
        )

        val state = OtaPollWorker.runCycle(deps)

        assertEquals(PollState.Failed(RejectReason.SIGNATURE_INVALID), state)
        // The poisoned/rejected artifact must still be cleaned up regardless of which
        // specific reason rejected it.
        assertEquals("/data/ota_package/ota_update.zip", downloader.deletedPath)
    }

    /** Same regression, the MALFORMED_DIGEST branch. */
    @Test
    fun `verify rejection propagates MALFORMED_DIGEST, not a hardcoded HASH_MISMATCH`() = runBlocking {
        val deps = AgentDependencies(
            client = FakeControlPlaneClient(PollResult.Update(sampleAssignment())),
            downloader = FakeDownloader(
                DownloadResult(outcome = DownloadOutcome.Downloaded, localPath = "/data/ota_package/ota_update.zip"),
            ),
            verifier = FakeVerifier(VerifyResult.Rejected(RejectReason.MALFORMED_DIGEST)),
            applyPort = FakeApplyPort(ApplyResult.Launched),
            telemetry = FakeTelemetry(),
        )

        val state = OtaPollWorker.runCycle(deps)

        assertEquals(PollState.Failed(RejectReason.MALFORMED_DIGEST), state)
    }
}
