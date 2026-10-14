package dev.tmn.watchtest.bridge

/** Shares the ANCS control point with notification requests; never send concurrently. */
class AncsAppAttributes(val app: String) {
    private val appBytes = app.toByteArray(Charsets.UTF_8)
    private var buffer = ByteArray(0)
    private var acknowledged = false
    private var response: String? = null
    init { require(appBytes.size in 1..1024 && 0.toByte() !in appBytes) }
    fun request(): ByteArray = byteArrayOf(1) + appBytes + byteArrayOf(0, 0)
    fun acknowledgeWrite() { acknowledged = true }
    val complete get() = acknowledged && response != null
    val name get() = checkNotNull(response)
    fun append(fragment: ByteArray) {
        check(response == null) { "Extra app response" }
        require(buffer.size + fragment.size <= 16 * 1024) { "App response exceeded limit" }
        buffer += fragment
        val header = byteArrayOf(1) + appBytes + byteArrayOf(0, 0)
        for (i in 0 until minOf(buffer.size, header.size)) require(buffer[i] == header[i]) { "Unexpected app response" }
        if (buffer.size < header.size + 2) return
        val length = (buffer[header.size].toInt() and 255) or ((buffer[header.size + 1].toInt() and 255) shl 8)
        val end = header.size + 2 + length
        if (buffer.size < end) return
        require(buffer.size == end) { "Trailing app response" }
        response = String(buffer, header.size + 2, length, Charsets.UTF_8)
    }
}
