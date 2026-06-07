/*
 * Helix OTA — protocol DTOs (1.0.0-MVP).
 * Kotlin data classes mirroring the OTA REST protocol (api/endpoints.md §12.1, §12.2).
 * Pure Kotlin — manual JSON via [OtaJson], no kotlinx.serialization dependency.
 */
package digital.vasic.helix.ota.core.protocol

/**
 * The four `payload_properties.txt` values passed straight through to
 * `update_engine.applyPayload(...)` (android-update-engine-api §4/§6, endpoints.md §12.1).
 * `*_HASH` are base64(SHA-256); `*_SIZE` are decimal bytes.
 */
data class PayloadProperties(
    val fileHashB64: String,      // FILE_HASH
    val fileSize: Long,           // FILE_SIZE
    val metadataHashB64: String,  // METADATA_HASH
    val metadataSize: Long,       // METADATA_SIZE
) {
    /** Header array form for applyPayload's keyValuePairHeaders. */
    fun asHeaderArray(): List<String> = listOf(
        "FILE_HASH=$fileHashB64",
        "FILE_SIZE=$fileSize",
        "METADATA_HASH=$metadataHashB64",
        "METADATA_SIZE=$metadataSize",
    )
}

/**
 * Update-check request the agent sends to `GET /api/v1/client/update`.
 * The calling deviceId comes from the token `sub`; `currentVersion` is an
 * optional short-circuit hint (endpoints.md §12.1).
 */
data class UpdateCheckRequest(
    val deviceId: String,
    val currentVersion: String?,
)

/**
 * `200 OK` `UpdateAvailable` body from `GET /api/v1/client/update` (endpoints.md §12.1).
 * Gives the device exactly what `applyPayload(url, offset, size, headers)` needs.
 */
data class UpdateAvailable(
    val releaseId: String,
    val version: String,
    val url: String,
    val offset: Long,
    val size: Long,
    val sha256: String,          // lowercase-hex SHA-256 of the artifact
    val signature: String,       // base64 detached signature over the artifact
    val payloadProperties: PayloadProperties,
)

/** Per-device health snapshot attached to a telemetry batch (endpoints.md §12.2). */
data class DeviceHealth(
    val batteryPct: Int?,
    val storageFreeMb: Long?,
    val activeSlot: String?,
)

/** One telemetry lifecycle event (endpoints.md §12.2). */
data class TelemetryEventRecord(
    val event: TelemetryEvent,
    val version: String,
    val timestamp: String,       // RFC3339 / ISO-8601 string
    val errorCode: String?,
    val detail: String?,
)

/** Batch body for `POST /api/v1/client/telemetry` (endpoints.md §12.2). */
data class TelemetryReport(
    val deviceId: String,
    val deploymentId: String?,
    val events: List<TelemetryEventRecord>,
    val health: DeviceHealth?,
)

/** `202 Accepted` `TelemetryAck` response (endpoints.md §12.2). */
data class TelemetryAck(
    val accepted: Int,
    val rejected: Int,
    val requestId: String,
)
