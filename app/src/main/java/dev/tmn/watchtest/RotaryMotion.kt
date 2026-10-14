package dev.tmn.watchtest

import kotlin.math.abs
import kotlin.math.sign

/** Velocity-sensitive crown motion, independent of frame rate and UI toolkit. */
class RotaryMotion {
    private var lastTime = 0L
    private var direction = 0f
    var velocity = 0f
        private set

    fun step(pixels: Float, time: Long): Float {
        if (pixels == 0f) return 0f
        val elapsed = time - lastTime
        val continuing = lastTime != 0L && elapsed in 1..180 && pixels.sign == direction
        if (!continuing) velocity = 0f
        val speed = if (continuing) abs(pixels) * 1000f / elapsed.coerceAtLeast(8) else 0f
        val gain = (1.25f + speed / 900f).coerceIn(1.25f, 3.5f)
        val delta = pixels * gain
        if (continuing) velocity = (velocity * .4f + delta * 1000f / elapsed.coerceAtLeast(8) * .6f).coerceIn(-6000f, 6000f)
        direction = pixels.sign; lastTime = time
        return delta
    }
    fun reset() { lastTime = 0; direction = 0f; velocity = 0f }
}
