/*
 * Helix OTA — ApplyPort implementation that drives android.os.UpdateEngine via REFLECTION.
 *
 * WHY REFLECTION: android.os.UpdateEngine / UpdateEngineCallback are @SystemApi and are NOT
 * present in the public android.jar this module compiles against. Calling them directly would
 * not compile against the public SDK. Reflection lets THIS module compile against the public
 * SDK while still invoking the real engine at runtime on a system-UID/platform-signed build
 * (integration guide §10; update_engine_integration.md). On a non-system build the class is
 * absent and applyVerified returns Failed — it never fabricates success.
 *
 * This class performs NO trust checks: the file handed in is already verified by :core
 * VerifyBeforeApply. It only calls applyPayload(file://…, offset, size, headers).
 */
package digital.vasic.helix.ota.agent.apply

class ReflectiveUpdateEngineApplyPort : ApplyPort {

    override fun applyVerified(pkg: VerifiedPackage): ApplyResult {
        return try {
            // Class.forName resolves android.os.UpdateEngine only on a system image where the
            // @SystemApi surface exists; on a normal SDK build it throws ClassNotFoundException.
            val engineClass = Class.forName("android.os.UpdateEngine")
            val engine = engineClass.getConstructor().newInstance()

            // public void applyPayload(String url, long offset, long size, String[] headers)
            val applyPayload = engineClass.getMethod(
                "applyPayload",
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Array<String>::class.java,
            )

            applyPayload.invoke(
                engine,
                "file://${pkg.localPath}",
                pkg.payloadOffset,
                pkg.payloadSize,
                pkg.props.asHeaderArray().toTypedArray(),
            )
            // update_engine stages the slot; the actual reboot is triggered out-of-band
            // (UpdateEngine has no reboot API — PowerManager.reboot(null) elsewhere).
            ApplyResult.Launched
        } catch (t: Throwable) {
            // ClassNotFoundException (non-system build), reflection failure, or engine error.
            ApplyResult.Failed(t)
        }
    }
}
