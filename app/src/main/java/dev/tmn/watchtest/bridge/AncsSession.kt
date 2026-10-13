package dev.tmn.watchtest.bridge

/** Serialized, bounded request state. The service confines this to its main looper. */
class AncsSession(private val capacity: Int = 64) {
    data class Result(val event: AncsProtocol.Event, val fields: Map<Int, String>)
    private val pending = linkedMapOf<Long, AncsProtocol.Event>()
    var current: AncsProtocol.Event? = null
        private set
    private var assembler: AncsAttributeAssembler? = null
    private var removed = false
    private var writeComplete = false
    private var response: Map<Int, String>? = null
    val pendingCount get() = pending.size

    fun enqueue(event: AncsProtocol.Event) {
        if (event.eventId == AncsProtocol.EVENT_REMOVED) {
            pending.remove(event.uid)
            if (current?.uid == event.uid) removed = true
            return
        }
        pending.remove(event.uid)
        if (pending.size >= capacity) pending.remove(pending.keys.first())
        pending[event.uid] = event
    }

    fun next(): AncsProtocol.Event? {
        if (current != null || pending.isEmpty()) return null
        // Fresh notifications should not wait behind the initial iPhone backlog.
        val next = pending.values.firstOrNull { it.flags and 4 == 0 } ?: pending.values.first()
        val event = pending.remove(next.uid)!!
        current = event
        assembler = AncsAttributeAssembler(event.uid, (0..7).toList())
        removed = false
        writeComplete = false
        response = null
        return event
    }

    fun append(bytes: ByteArray) {
        check(response == null) { "Extra ANCS response" }
        response = assembler?.append(bytes)
    }

    fun acknowledgeWrite() { writeComplete = true }
    val complete get() = current != null && writeComplete && response != null

    fun finish(): Result? {
        check(complete)
        val result = if (removed || pending.containsKey(current!!.uid)) null else Result(current!!, response!!)
        discardCurrent()
        return result
    }

    fun discardCurrent() {
        current = null
        assembler = null
        response = null
        removed = false
        writeComplete = false
    }

    fun clear() { pending.clear(); discardCurrent() }
}
