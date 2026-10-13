package dev.tmn.watchtest.bridge

/** ANCS wire protocol helpers. All integer fields are little endian. */
object AncsProtocol {
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    data class Event(
        val eventId: Int,
        val flags: Int,
        val category: Int,
        val categoryCount: Int,
        val uid: Long,
    )

    fun parseEvent(bytes: ByteArray): Event? {
        if (bytes.size != 8) return null
        return Event(
            eventId = bytes[0].toInt() and 0xff,
            flags = bytes[1].toInt() and 0xff,
            category = bytes[2].toInt() and 0xff,
            categoryCount = bytes[3].toInt() and 0xff,
            uid = uint32le(bytes, 4),
        ).takeIf { it.eventId in EVENT_ADDED..EVENT_REMOVED }
    }

    /** Request all useful text fields. Only title, subtitle and message accept two-byte limits. */
    fun getNotificationAttributes(uid: Long, maxTextBytes: Int = 512): ByteArray {
        require(uid in 0..0xffffffffL)
        require(maxTextBytes in 1..65535)
        val ids = listOf(ATTR_APP_IDENTIFIER, ATTR_TITLE, ATTR_SUBTITLE, ATTR_MESSAGE,
            ATTR_MESSAGE_SIZE, ATTR_DATE, ATTR_POSITIVE_ACTION_LABEL, ATTR_NEGATIVE_ACTION_LABEL)
        val out = ArrayList<Byte>(5 + ids.size * 3)
        out += 0
        for (shift in 0..24 step 8) out += ((uid ushr shift) and 0xff).toByte()
        ids.forEach { id ->
            out += id.toByte()
            if (id == ATTR_TITLE || id == ATTR_SUBTITLE || id == ATTR_MESSAGE) {
                out += (maxTextBytes and 0xff).toByte()
                out += ((maxTextBytes ushr 8) and 0xff).toByte()
            }
        }
        return out.toByteArray()
    }

    private fun uint32le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
}

/** Reassembles one serialized Get Notification Attributes response across GATT chunks. */
class AncsAttributeAssembler(
    private val uid: Long,
    private val requestedIds: List<Int>,
    private val maxResponseBytes: Int = 16 * 1024,
) {
    private var buffer = ByteArray(0)

    fun append(fragment: ByteArray): Map<Int, String>? {
        if (buffer.size + fragment.size > maxResponseBytes) {
            reset()
            throw IllegalArgumentException("ANCS response exceeded limit")
        }
        buffer += fragment
        if (buffer.size < 5) return null
        val responseUid = (buffer[1].toLong() and 0xff) or
            ((buffer[2].toLong() and 0xff) shl 8) or
            ((buffer[3].toLong() and 0xff) shl 16) or
            ((buffer[4].toLong() and 0xff) shl 24)
        require(buffer[0].toInt() == 0 && responseUid == uid) { "Unexpected ANCS response" }

        var offset = 5
        val values = linkedMapOf<Int, String>()
        for (expected in requestedIds) {
            if (buffer.size - offset < 3) return null
            val id = buffer[offset++].toInt() and 0xff
            val length = (buffer[offset++].toInt() and 0xff) or ((buffer[offset++].toInt() and 0xff) shl 8)
            require(id == expected) { "Unexpected ANCS attribute $id; expected $expected" }
            if (buffer.size - offset < length) return null
            values[id] = String(buffer, offset, length, Charsets.UTF_8)
            offset += length
        }
        require(offset == buffer.size) { "Trailing ANCS response data" }
        reset()
        return values
    }

    fun reset() { buffer = ByteArray(0) }
}
