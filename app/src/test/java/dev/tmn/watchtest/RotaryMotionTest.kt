package dev.tmn.watchtest

import org.junit.Assert.*
import org.junit.Test

class RotaryMotionTest {
    @Test fun fastTurnsAccelerateAndReverseStopsPreviousMomentum() {
        val slow = RotaryMotion(); slow.step(10f, 1000)
        val slowStep = slow.step(10f, 1140)
        val fast = RotaryMotion(); fast.step(10f, 1000)
        assertTrue(fast.step(10f, 1010) > slowStep)
        assertTrue(fast.velocity > slow.velocity)
        assertTrue(fast.step(-10f, 1020) < 0)
        assertEquals(0f, fast.velocity, 0f)
    }
    @Test fun pauseAndResetDropOldVelocityAndGainIsBounded() {
        val motion = RotaryMotion(); motion.step(100f, 1000)
        assertTrue(motion.step(100f, 1001) <= 350f)
        assertTrue(motion.velocity <= 6000f)
        assertEquals(125f, motion.step(100f, 1400), 0f)
        assertEquals(0f, motion.velocity, 0f)
        motion.reset()
        assertEquals(1.25f, motion.step(1f, 1500), 0f)
    }
}
