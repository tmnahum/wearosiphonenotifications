package dev.tmn.watchtest.bridge

object NotificationLogRetention {
    const val DAYS = 14L
    fun cutoff(nowMillis: Long): Long = nowMillis - DAYS * 24 * 60 * 60 * 1000
}
