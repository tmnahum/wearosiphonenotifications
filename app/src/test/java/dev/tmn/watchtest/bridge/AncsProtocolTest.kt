package dev.tmn.watchtest.bridge

import org.junit.Assert.*
import org.junit.Test

class AncsProtocolTest {
    private val uid = 0xfedcba98L
    private val fields = listOf("test.chat", "Friends", "Alex", "Hello 🌍", "10", "20261013T120000", "", "Dismiss")
    private fun response(): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(byteArrayOf(0, 0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte()).toList())
        fields.forEachIndexed { id, value ->
            val bytes = value.toByteArray()
            out.add(id.toByte()); out.add(bytes.size.toByte()); out.add(0)
            out.addAll(bytes.toList())
        }
        return out.toByteArray()
    }
    private fun event(kind: Int = 0, id: Long = uid) = AncsProtocol.Event(kind, 0, 4, 1, id)

    @Test fun requestMatchesAppleWireFormat() {
        assertArrayEquals(byteArrayOf(0, 0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(),
            0, 1, 0, 2, 2, 0, 2, 3, 0, 2, 4, 5, 6, 7), AncsProtocol.getNotificationAttributes(uid))
    }
    @Test fun unsignedUidAndInvalidEvents() {
        assertEquals(uid, AncsProtocol.parseEvent(byteArrayOf(0, 0, 4, 1, 0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte()))!!.uid)
        assertNull(AncsProtocol.parseEvent(ByteArray(7)))
        assertNull(AncsProtocol.parseEvent(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)))
    }
    @Test fun everyPossibleFragmentBoundaryIncludingUtf8() {
        val bytes = response()
        for (split in 1 until bytes.size) {
            val assembler = AncsAttributeAssembler(uid, (0..7).toList())
            assertNull(assembler.append(bytes.copyOfRange(0, split)))
            assertEquals(fields, assembler.append(bytes.copyOfRange(split, bytes.size))!!.values.toList())
        }
    }
    @Test fun serviceSessionRetainsEverySingleByteFragment() {
        val session = AncsSession()
        session.enqueue(event()); session.next(); session.acknowledgeWrite()
        val bytes = response()
        bytes.forEachIndexed { index, byte ->
            session.append(byteArrayOf(byte))
            assertEquals(index == bytes.lastIndex, session.complete)
        }
        assertEquals(fields, session.finish()!!.fields.values.toList())
        assertNull(session.current)
    }
    @Test fun responseBeforeWriteCallbackWaitsForAcknowledgement() {
        val session = AncsSession()
        session.enqueue(event()); session.next(); session.append(response())
        assertFalse(session.complete)
        session.acknowledgeWrite()
        assertTrue(session.complete)
    }
    @Test fun removalDuringResponseDoesNotResurrectNotification() {
        val session = AncsSession()
        session.enqueue(event()); session.next(); session.acknowledgeWrite()
        session.enqueue(event(2)); session.append(response())
        assertTrue(session.complete)
        assertNull(session.finish())
    }
    @Test fun modificationSupersedesOldResponseAndQueueIsBounded() {
        val session = AncsSession(2)
        session.enqueue(event()); session.next(); session.acknowledgeWrite()
        session.enqueue(event(1)); session.append(response())
        assertNull(session.finish())
        assertEquals(1, session.next()!!.eventId)
        session.enqueue(event(id = 1)); session.enqueue(event(id = 2)); session.enqueue(event(id = 3))
        assertEquals(2, session.pendingCount)
        session.clear()
        assertNull(session.next())
        assertNull(session.current)
    }
    @Test fun freshMessageTakesPriorityOverExistingBacklog() {
        val session = AncsSession()
        session.enqueue(event(id = 1).copy(flags = 4))
        session.enqueue(event(id = 2).copy(flags = 4))
        session.enqueue(event(id = 3))
        assertEquals(3L, session.next()!!.uid)
        session.discardCurrent()
        assertEquals(1L, session.next()!!.uid)
    }
    @Test fun malformedAndOversizedResponsesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AncsAttributeAssembler(1, (0..7).toList()).append(response())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AncsAttributeAssembler(uid, (0..7).toList(), 8).append(response())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AncsAttributeAssembler(uid, (0..7).toList()).append(response() + byteArrayOf(0))
        }
    }
}
