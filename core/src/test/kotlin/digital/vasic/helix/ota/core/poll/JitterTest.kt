package digital.vasic.helix.ota.core.poll

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JitterTest {

    private val base = 15 * 60_000L      // 15 min
    private val jitterMax = 5 * 60_000L  // 5 min

    @Test
    fun delayWithinBounds_overManySeededDraws() {
        val rng = Random(42)
        repeat(10_000) {
            val d = Jitter.nextDelayMillis(base, jitterMax, rng)
            assertTrue(d >= base, "delay $d below base $base")
            assertTrue(d < base + jitterMax, "delay $d >= base+jitterMax ${base + jitterMax}")
        }
    }

    @Test
    fun seededRng_isDeterministic() {
        val a = Jitter.nextDelayMillis(base, jitterMax, Random(7))
        val b = Jitter.nextDelayMillis(base, jitterMax, Random(7))
        assertEquals(a, b)
    }

    @Test
    fun zeroJitter_returnsExactlyBase() {
        assertEquals(base, Jitter.nextDelayMillis(base, 0L, Random(1)))
    }

    @Test
    fun zeroBase_stillBoundedByJitter() {
        val d = Jitter.nextDelayMillis(0L, jitterMax, Random(99))
        assertTrue(d in 0 until jitterMax)
    }

    @Test
    fun negativeBase_throws() {
        assertFailsWith<IllegalArgumentException> {
            Jitter.nextDelayMillis(-1L, jitterMax, Random(1))
        }
    }

    @Test
    fun negativeJitter_throws() {
        assertFailsWith<IllegalArgumentException> {
            Jitter.nextDelayMillis(base, -1L, Random(1))
        }
    }

    @Test
    fun defaultRng_isUsedWhenRngOmitted() {
        // Calling the 2-arg form (rng defaulted to Random.Default) exercises the
        // `nextDelayMillis$default` bridge. With zero jitter the result is deterministic
        // (exactly base) so the default-RNG path is asserted without flakiness.
        assertEquals(base, Jitter.nextDelayMillis(base, 0L))
    }

    @Test
    fun defaultRng_withJitter_staysWithinBounds() {
        // Non-zero jitter via the default RNG: the draw is non-deterministic but MUST
        // remain within [base, base+jitterMax). Proves the default RNG actually draws.
        repeat(1_000) {
            val d = Jitter.nextDelayMillis(base, jitterMax)
            assertTrue(d >= base, "delay $d below base $base")
            assertTrue(d < base + jitterMax, "delay $d >= base+jitterMax ${base + jitterMax}")
        }
    }
}
