/*
 * Helix OTA — DTO <-> JSON codecs built on the dependency-free [Json] model.
 * Field names match the REST wire contract (api/endpoints.md §12.1, §12.2).
 */
package digital.vasic.helix.ota.core.protocol

import digital.vasic.helix.ota.core.json.Json
import digital.vasic.helix.ota.core.json.JsonValue
import digital.vasic.helix.ota.core.json.arr
import digital.vasic.helix.ota.core.json.asObj
import digital.vasic.helix.ota.core.json.intOrNull
import digital.vasic.helix.ota.core.json.long
import digital.vasic.helix.ota.core.json.longOrNull
import digital.vasic.helix.ota.core.json.obj
import digital.vasic.helix.ota.core.json.objOrNull
import digital.vasic.helix.ota.core.json.str
import digital.vasic.helix.ota.core.json.strOrNull

object OtaCodec {

    // ---- PayloadProperties (the four applyPayload header values) ----

    fun toJson(p: PayloadProperties): JsonValue.Obj = Json.obj(
        "FILE_HASH" to Json.str(p.fileHashB64),
        "FILE_SIZE" to Json.num(p.fileSize),
        "METADATA_HASH" to Json.str(p.metadataHashB64),
        "METADATA_SIZE" to Json.num(p.metadataSize),
    )

    fun payloadProperties(o: JsonValue.Obj): PayloadProperties = PayloadProperties(
        fileHashB64 = o.str("FILE_HASH"),
        fileSize = o.long("FILE_SIZE"),
        metadataHashB64 = o.str("METADATA_HASH"),
        metadataSize = o.long("METADATA_SIZE"),
    )

    // ---- UpdateCheckRequest ----

    fun toJson(r: UpdateCheckRequest): JsonValue.Obj = Json.obj(
        "device_id" to Json.str(r.deviceId),
        "current_version" to Json.str(r.currentVersion),
    )

    fun updateCheckRequest(json: String): UpdateCheckRequest {
        val o = Json.parse(json).asObj()
        return UpdateCheckRequest(
            deviceId = o.str("device_id"),
            currentVersion = o.strOrNull("current_version"),
        )
    }

    // ---- UpdateAvailable ----

    fun toJson(u: UpdateAvailable): JsonValue.Obj = Json.obj(
        "release_id" to Json.str(u.releaseId),
        "version" to Json.str(u.version),
        "url" to Json.str(u.url),
        "offset" to Json.num(u.offset),
        "size" to Json.num(u.size),
        "sha256" to Json.str(u.sha256),
        "signature" to Json.str(u.signature),
        "payload_properties" to toJson(u.payloadProperties),
    )

    fun updateAvailable(json: String): UpdateAvailable {
        val o = Json.parse(json).asObj()
        return UpdateAvailable(
            releaseId = o.str("release_id"),
            version = o.str("version"),
            url = o.str("url"),
            offset = o.long("offset"),
            size = o.long("size"),
            sha256 = o.str("sha256"),
            signature = o.str("signature"),
            payloadProperties = payloadProperties(o.obj("payload_properties")),
        )
    }

    // ---- TelemetryReport ----

    fun toJson(r: TelemetryReport): JsonValue.Obj = Json.obj(
        "device_id" to Json.str(r.deviceId),
        "deployment_id" to Json.str(r.deploymentId),
        "events" to Json.arr(r.events.map { toJson(it) }),
        "health" to (r.health?.let { toJson(it) } ?: JsonValue.Null),
    )

    fun telemetryReport(json: String): TelemetryReport {
        val o = Json.parse(json).asObj()
        return TelemetryReport(
            deviceId = o.str("device_id"),
            deploymentId = o.strOrNull("deployment_id"),
            events = o.arr("events").items.map { telemetryEventRecord(it.asObj()) },
            health = o.objOrNull("health")?.let { deviceHealth(it) },
        )
    }

    private fun toJson(e: TelemetryEventRecord): JsonValue.Obj = Json.obj(
        "event" to Json.str(e.event.wire),
        "version" to Json.str(e.version),
        "timestamp" to Json.str(e.timestamp),
        "error_code" to Json.str(e.errorCode),
        "detail" to Json.str(e.detail),
    )

    private fun telemetryEventRecord(o: JsonValue.Obj): TelemetryEventRecord = TelemetryEventRecord(
        event = TelemetryEvent.fromWire(o.str("event")),
        version = o.str("version"),
        timestamp = o.str("timestamp"),
        errorCode = o.strOrNull("error_code"),
        detail = o.strOrNull("detail"),
    )

    private fun toJson(h: DeviceHealth): JsonValue.Obj = Json.obj(
        "battery_pct" to Json.num(h.batteryPct),
        "storage_free_mb" to Json.num(h.storageFreeMb),
        "active_slot" to Json.str(h.activeSlot),
    )

    private fun deviceHealth(o: JsonValue.Obj): DeviceHealth = DeviceHealth(
        batteryPct = o.intOrNull("battery_pct"),
        storageFreeMb = o.longOrNull("storage_free_mb"),
        activeSlot = o.strOrNull("active_slot"),
    )

    // ---- TelemetryAck ----

    fun toJson(a: TelemetryAck): JsonValue.Obj = Json.obj(
        "accepted" to Json.num(a.accepted),
        "rejected" to Json.num(a.rejected),
        "request_id" to Json.str(a.requestId),
    )

    fun telemetryAck(json: String): TelemetryAck {
        val o = Json.parse(json).asObj()
        return TelemetryAck(
            accepted = o.intOrNull("accepted") ?: 0,
            rejected = o.intOrNull("rejected") ?: 0,
            requestId = o.str("request_id"),
        )
    }

    // Convenience: serialize any DTO obj to a string.
    fun encode(value: JsonValue): String = Json.write(value)
}
