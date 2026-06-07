/*
 * Helix OTA — poll-delay jitter computation (integration guide §6).
 * Jitter spreads fleet poll times so millions of devices do not stampede the
 * control plane at the same instant (master §1 scalability guarantee).
 *
 * Pure function with an INJECTABLE RNG so the bound behaviour is deterministically
 * testable with a seeded RNG. Values (baseMillis, jitterMaxMillis) come from Config-KMP.
 *
 *   next delay = baseMillis + uniform[0, jitterMaxMillis]
 */
package digital.vasic.helix.ota.core.poll

import kotlin.random.Random

object Jitter {

    /**
     * Compute the next poll delay in milliseconds.
     *
     * @param baseMillis      the locked baseline cadence (e.g. 15 min) in ms; must be >= 0.
     * @param jitterMaxMillis upper bound (inclusive of 0, exclusive of max) of the uniform
     *                        jitter draw; 0 disables jitter; must be >= 0.
     * @param rng             injectable source of randomness (seed it in tests).
     * @return baseMillis + uniform[0, jitterMaxMillis).
     */
    fun nextDelayMillis(
        baseMillis: Long,
        jitterMaxMillis: Long,
        rng: Random = Random.Default,
    ): Long {
        require(baseMillis >= 0) { "baseMillis must be >= 0, was $baseMillis" }
        require(jitterMaxMillis >= 0) { "jitterMaxMillis must be >= 0, was $jitterMaxMillis" }
        val jitter = if (jitterMaxMillis == 0L) 0L else rng.nextLong(0, jitterMaxMillis)
        return baseMillis + jitter
    }
}
