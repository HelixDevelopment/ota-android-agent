/*
 * Helix OTA — protocol enums (1.0.0-MVP).
 * Mirrors the wire contracts owned by `ota-protocol` / the REST API spec
 * (api/endpoints.md §8.2 / §12.1 / §12.2). Pure Kotlin — no Android, no kotlinx.
 */
package digital.vasic.helix.ota.core.protocol

/**
 * Device lifecycle state — the 7-value `UpdateState` schema (endpoints.md §8.2).
 * Includes `IDLE`. Used by /devices/{deviceId}/status.
 */
enum class UpdateState(val wire: String) {
    IDLE("idle"),
    DOWNLOAD_STARTED("download_started"),
    INSTALLING("installing"),
    INSTALLED("installed"),
    VERIFYING("verifying"),
    SUCCESS("success"),
    FAILURE("failure");

    companion object {
        fun fromWire(value: String): UpdateState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown UpdateState wire value: $value")
    }
}

/**
 * Telemetry event — the 6-value `TelemetryEventType` schema (endpoints.md §12.2).
 * Same lifecycle as [UpdateState] MINUS `idle` (an idle device emits no telemetry event).
 * These two enums are intentionally distinct types.
 */
enum class TelemetryEvent(val wire: String) {
    DOWNLOAD_STARTED("download_started"),
    INSTALLING("installing"),
    INSTALLED("installed"),
    VERIFYING("verifying"),
    SUCCESS("success"),
    FAILURE("failure");

    companion object {
        fun fromWire(value: String): TelemetryEvent =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown TelemetryEvent wire value: $value")
    }
}
