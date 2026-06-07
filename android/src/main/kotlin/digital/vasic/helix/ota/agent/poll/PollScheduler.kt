/*
 * Helix OTA — WorkManager poll scheduling: 15 min + configurable jitter (integration guide §6).
 * Cadence/jitter come from Config-KMP (runtime-tunable per fleet). WorkManager enforces a
 * 15-min minimum periodic interval, which matches the locked cadence (master §5 / D7).
 *
 * JITTER NOTE: setInitialDelay(...) only offsets the FIRST run after a (re)schedule — it does
 * NOT re-randomize each periodic cycle. The MVP applies jitter at schedule time; per-cycle
 * spread (flex window / self-rescheduling OneTimeWorkRequest) is a documented follow-up.
 * The jitter VALUE is computed by the pure :core Jitter function (seeded-testable).
 */
package digital.vasic.helix.ota.agent.poll

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import digital.vasic.helix.ota.core.poll.Jitter
import java.time.Duration
import kotlin.random.Random

/** Poll config sourced from Config-KMP (illustrative surface). */
data class PollConfig(
    val periodMinutes: Long = 15L,             // locked baseline cadence
    val jitterMaxMillis: Long = 5 * 60_000L,
    val requireUnmetered: Boolean = false,
    val requireCharging: Boolean = false,
)

object PollScheduler {

    const val WORK_NAME = "helix-ota-poll"

    fun schedule(wm: WorkManager, cfg: PollConfig, rng: Random = Random.Default) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (cfg.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresCharging(cfg.requireCharging)
            .build()

        // Jitter spreads fleet poll times so millions of devices don't stampede the control
        // plane (scalability guarantee, master §1). Base = 0 here because setInitialDelay is a
        // pure offset; we only want the uniform[0, jitterMax) component as the first-run delay.
        val initialDelayMillis = Jitter.nextDelayMillis(
            baseMillis = 0L,
            jitterMaxMillis = cfg.jitterMaxMillis,
            rng = rng,
        )

        val request = PeriodicWorkRequestBuilder<OtaPollWorker>(
            Duration.ofMinutes(cfg.periodMinutes),
        )
            .setConstraints(constraints)
            .setInitialDelay(Duration.ofMillis(initialDelayMillis))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .addTag(WORK_NAME)
            .build()

        // UPDATE: replace the existing unique work in place so a reschedule applies new
        // jitter/config without duplicating or dropping work (KEEP would retain old params).
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
