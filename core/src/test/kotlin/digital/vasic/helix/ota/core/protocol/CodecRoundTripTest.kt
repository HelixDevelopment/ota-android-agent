package digital.vasic.helix.ota.core.protocol

import digital.vasic.helix.ota.core.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CodecRoundTripTest {

    private val props = PayloadProperties(
        fileHashB64 = "ZmlsZWhhc2g=",
        fileSize = 379074366L,
        metadataHashB64 = "bWV0YWhhc2g=",
        metadataSize = 46866L,
    )

    @Test
    fun updateAvailable_roundTrip() {
        val u = UpdateAvailable(
            releaseId = "r77e-uuid",
            version = "1.1.0",
            url = "https://artifacts.helix.example/a91c.zip",
            offset = 1234L,
            size = 379074366L,
            sha256 = "9f86d0",
            signature = "BASE64-detached-signature",
            payloadProperties = props,
        )
        val json = OtaCodec.encode(OtaCodec.toJson(u))
        val back = OtaCodec.updateAvailable(json)
        assertEquals(u, back)
    }

    @Test
    fun updateAvailable_parsesSpecExampleShape() {
        val json = """
            {
              "release_id": "r77e-uuid",
              "version": "1.1.0",
              "url": "https://artifacts.helix.example/a91c.zip",
              "offset": 1234,
              "size": 379074366,
              "sha256": "9f86d0",
              "signature": "BASE64-detached-signature",
              "payload_properties": {
                "FILE_HASH": "base64hash",
                "FILE_SIZE": 379074366,
                "METADATA_HASH": "base64meta",
                "METADATA_SIZE": 46866
              }
            }
        """.trimIndent()
        val u = OtaCodec.updateAvailable(json)
        assertEquals("1.1.0", u.version)
        assertEquals(1234L, u.offset)
        assertEquals("base64hash", u.payloadProperties.fileHashB64)
        assertEquals(46866L, u.payloadProperties.metadataSize)
    }

    @Test
    fun updateCheckRequest_roundTrip_withNullVersion() {
        val r = UpdateCheckRequest(deviceId = "dev-1", currentVersion = null)
        val back = OtaCodec.updateCheckRequest(OtaCodec.encode(OtaCodec.toJson(r)))
        assertEquals(r, back)
        assertNull(back.currentVersion)
    }

    @Test
    fun telemetryReport_roundTrip_allSixEventTypes() {
        val events = TelemetryEvent.entries.map {
            TelemetryEventRecord(
                event = it,
                version = "1.1.0",
                timestamp = "2026-06-07T00:15:00Z",
                errorCode = if (it == TelemetryEvent.FAILURE) "PAYLOAD_VERIFICATION_FAILED" else null,
                detail = if (it == TelemetryEvent.FAILURE) "FILE_HASH mismatch" else null,
            )
        }
        val report = TelemetryReport(
            deviceId = "8f3a-uuid",
            deploymentId = "d12b-uuid",
            events = events,
            health = DeviceHealth(batteryPct = 88, storageFreeMb = 4096L, activeSlot = "a"),
        )
        val back = OtaCodec.telemetryReport(OtaCodec.encode(OtaCodec.toJson(report)))
        assertEquals(report, back)
        assertEquals(6, back.events.size)
    }

    @Test
    fun telemetryReport_roundTrip_nullHealthAndDeployment() {
        val report = TelemetryReport(
            deviceId = "dev",
            deploymentId = null,
            events = listOf(
                TelemetryEventRecord(TelemetryEvent.SUCCESS, "1.1.0", "2026-06-07T00:20:00Z", null, null),
            ),
            health = null,
        )
        val back = OtaCodec.telemetryReport(OtaCodec.encode(OtaCodec.toJson(report)))
        assertEquals(report, back)
        assertNull(back.health)
        assertNull(back.deploymentId)
    }

    @Test
    fun telemetryAck_roundTrip() {
        val ack = TelemetryAck(accepted = 2, rejected = 0, requestId = "01J-ulid")
        assertEquals(ack, OtaCodec.telemetryAck(OtaCodec.encode(OtaCodec.toJson(ack))))
    }

    @Test
    fun telemetryEvent_hasExactlySixValues_noIdle() {
        assertEquals(6, TelemetryEvent.entries.size)
        assertEquals(
            listOf("download_started", "installing", "installed", "verifying", "success", "failure"),
            TelemetryEvent.entries.map { it.wire },
        )
    }

    @Test
    fun updateState_hasSevenValues_includingIdle() {
        assertEquals(7, UpdateState.entries.size)
        assertEquals(UpdateState.IDLE, UpdateState.fromWire("idle"))
    }

    @Test
    fun fromWire_unknownValue_throws() {
        assertFailsWith<IllegalArgumentException> { TelemetryEvent.fromWire("idle") }
        assertFailsWith<IllegalArgumentException> { UpdateState.fromWire("bogus") }
    }

    @Test
    fun json_escapesAndUnescapesSpecialChars() {
        val tricky = "line1\nline2\t\"quoted\" \\ backslash"
        val parsed = Json.parse(Json.write(Json.str(tricky)))
        assertEquals(tricky, (parsed as digital.vasic.helix.ota.core.json.JsonValue.Str).value)
    }

    @Test
    fun payloadProperties_asHeaderArray_matchesApplyPayloadContract() {
        assertEquals(
            listOf(
                "FILE_HASH=ZmlsZWhhc2g=",
                "FILE_SIZE=379074366",
                "METADATA_HASH=bWV0YWhhc2g=",
                "METADATA_SIZE=46866",
            ),
            props.asHeaderArray(),
        )
    }
}
