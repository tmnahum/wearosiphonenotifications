package dev.tmn.watchtest

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

/** Smooth short turns on animation frames; fast turns accelerate and coast with native fling. */
class CrownScrollController(private val view: View, private val scroll: (Int) -> Unit,
    private val fling: (Int) -> Unit) {
    private val motion = RotaryMotion()
    private val factor = ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    private var pending = 0f
    private var animating = false
    private val frame = object : Runnable {
        override fun run() {
            val step = if (abs(pending) < 2f) pending.roundToInt() else (pending * .45f).roundToInt()
            if (step != 0) { scroll(step); pending -= step }
            if (abs(pending) >= .5f) view.postOnAnimation(this) else animating = false
        }
    }
    private val coast = Runnable {
        view.removeCallbacks(frame); animating = false
        scroll(pending.roundToInt()); pending = 0f
        val velocity = motion.velocity.roundToInt()
        if (abs(velocity) >= 300) fling(velocity)
        motion.reset()
    }
    fun onRotary(event: MotionEvent) {
        fling(0) // A new turn takes control from any previous native coast.
        val pixels = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor
        if (pending * pixels < 0) pending = 0f
        pending += motion.step(pixels, event.eventTime)
        view.removeCallbacks(coast)
        view.postDelayed(coast, 90)
        if (!animating) { animating = true; view.postOnAnimation(frame) }
    }
    /** Let the view process ACTION_DOWN itself, including interrupting any native fling.
     * Calling ListView.fling(0) here enters its fling touch mode and swallows the tap.
     */
    fun onTouchDown() {
        view.removeCallbacks(frame); view.removeCallbacks(coast)
        pending = 0f; animating = false; motion.reset()
    }
    fun stop() {
        onTouchDown()
        fling(0)
    }
}
