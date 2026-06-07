# ota-android-agent

| Field | Value |
|---|---|
| Revision | 1 |
| Created | 2026-06-07 |
| Status | scaffold |
| Part of | [Helix OTA](https://github.com/HelixDevelopment/helix_ota) |
| Language | kotlin |
| License | Apache-2.0 |

## Purpose

Kotlin/KMP device agent: register, poll (interval+jitter), download, verify-before-apply, drive the update-engine bridge, report telemetry.

## Boundary (decoupling)

Consumes ota-protocol + ota-update-engine-bridge + ota-telemetry-schema; reuses Auth-KMP/Security-KMP/Storage-KMP. No server logic.

This is a **reusable, independently versioned** building brick (HelixConstitution
§11.4.28 submodules-as-equal-codebase). It is consumed by Helix OTA and is designed
to be reusable by other projects. It must ship in-depth documentation, user guides,
and full test coverage (§1 four-layer) before leaving `scaffold` status.

## Status

Scaffold. Implementation tracked in the Helix OTA spec corpus
(`docs/research/main_specs/`). See the master design and the submodule reuse map.

## Mirrors

- GitHub: https://github.com/HelixDevelopment/ota-android-agent
- GitLab: https://gitlab.com/helixdevelopment1/ota-android-agent
