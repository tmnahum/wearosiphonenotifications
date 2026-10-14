package dev.tmn.watchtest.bridge

/** Distinguish disappearance from the system tray from our own cancellations. */
class NativeTrayTracker {
    data class Missing(val records: Set<Long>, val clearedTogether: Boolean)
    private data class Posted(val ids: Set<Long>, val at: Long)
    private val cards = mutableMapOf<String, Posted>()
    fun isEmpty() = cards.isEmpty()
    fun posted(tag: String, ids: Set<Long>, now: Long = 0) { cards[tag] = Posted(ids.toSet(), now) }
    fun forget(tag: String) { cards.remove(tag) }
    fun forgetThrough(tag: String, throughId: Long) {
        if (cards[tag]?.ids?.all { it <= throughId } == true) cards.remove(tag)
    }
    fun reconcile(activeTags: Set<String>, now: Long = Long.MAX_VALUE): Missing {
        val gone = cards.keys.filter { it !in activeTags && now - cards.getValue(it).at >= 1000 }
        val together = gone.size > 1 && gone.size == cards.size
        val ids = gone.flatMap { cards.remove(it)?.ids.orEmpty() }.toSet()
        return Missing(ids, together)
    }
}
