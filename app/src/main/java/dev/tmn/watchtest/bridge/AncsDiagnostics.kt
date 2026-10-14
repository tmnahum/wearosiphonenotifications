package dev.tmn.watchtest.bridge

/** Main-thread, process-local state. No notification text is written to disk or logs. */
object AncsDiagnostics {
    var phase = ConnectionPhase.DISCONNECTED
    var problem = false
    var connected = false
    var running = false
    var status = "No active iPhone connection."
    val records = ArrayDeque<Pair<Long, String>>()
    val devices = linkedMapOf<String, String>()
    var events = 0
    var received = 0
    var posted = 0
    fun record(uid: Long, text: String) {
        records.removeAll { it.first == uid }
        records.addLast(uid to text)
        while (records.size > 6) records.removeFirst()
    }
    private data class Layout(val app: String, val title: String, val subtitle: String, val bodyStartsTitle: Boolean)
    private val layouts = linkedMapOf<Long, Layout>()
    @Synchronized fun recordLayout(uid: Long, fields: Map<Int, String>) {
        val title = fields[1].orEmpty()
        layouts[uid] = Layout(fields[0].orEmpty(), title, fields[2].orEmpty(),
            title.isNotBlank() && fields[3].orEmpty().startsWith("$title:"))
        while (layouts.size > 512) layouts.remove(layouts.keys.first())
    }
    @Synchronized fun removeLayout(uid: Long) { layouts.remove(uid) }
    /** Deliberately reports relationships only, never names or message bodies. */
    @Synchronized fun describeLayouts(query: String): List<String> = layouts.values
        .filter { it.title.contains(query, true) || it.subtitle.contains(query, true) }
        .map { "app=${it.app} titleEqualsGroup=${it.title.equals(query, true)} subtitleEqualsGroup=${it.subtitle.equals(query, true)} titleEndsInGroup=${it.title.endsWith(" in $query", true)} titleBlank=${it.title.isBlank()} subtitleBlank=${it.subtitle.isBlank()} bodyStartsTitle=${it.bodyStartsTitle}" }
        .distinct()
    @Synchronized fun clearSession() {
        layouts.clear()
        records.clear()
        events = 0
        received = 0
        posted = 0
    }
}
