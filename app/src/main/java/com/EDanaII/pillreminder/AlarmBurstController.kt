package com.EDanaII.pillreminder

internal data class AlarmPlaybackRequest(val occurrence: AlarmOccurrence, val alertAt: Long,
    val level: Int, val soundUri: String?, val vibration: Boolean, val escalation: Boolean, val unlocked: Boolean,
    val reminder: Reminder? = null) {
    val token get() = occurrence.token
}

/** Main-thread confined. One playing burst; at most one waiting burst per independent occurrence. */
internal class AlarmBurstController(
    private val output: Output,
    private val scheduleTimeout: (Long, () -> Unit) -> (() -> Unit),
    private val idle: () -> Unit
) {
    interface Output { fun start(request: AlarmPlaybackRequest); fun stop() }
    private val waiting = mutableListOf<AlarmPlaybackRequest>()
    private val completed = mutableMapOf<String, Long>()
    private val dismissed = mutableSetOf<String>()
    var current: AlarmPlaybackRequest? = null
        private set
    private var cancelTimeout: (() -> Unit)? = null

    fun enqueue(request: AlarmPlaybackRequest) {
        if (request.token in dismissed || (completed[request.token] ?: Long.MIN_VALUE) >= request.alertAt ||
            current?.let { it.token == request.token && it.alertAt >= request.alertAt } == true ||
            waiting.any { it.token == request.token && it.alertAt >= request.alertAt }) return
        waiting.removeAll { it.token == request.token }
        waiting.add(request)
        drain()
    }

    fun dismiss(token: String) {
        dismissTokens(listOf(token))
    }

    fun dismissTokens(tokens: Collection<String>) {
        // Include requests still loading asynchronously, not only those already in the queue.
        dismissed.addAll(tokens)
        waiting.removeAll { it.token in dismissed }
        if (current?.token in dismissed) finish() else if (current == null) drain()
    }

    fun dismissSchedule(id: Long, test: Boolean? = null) {
        fun matches(request: AlarmPlaybackRequest) = request.occurrence.reminderId == id &&
            (test == null || request.occurrence.test == test)
        val tokens = waiting.filter { matches(it) }.map { it.token }.toMutableList()
        current?.takeIf { matches(it) }?.let { tokens.add(it.token) }
        dismissTokens(tokens)
    }

    fun shutdown() {
        waiting.clear()
        cancelTimeout?.invoke()
        cancelTimeout = null
        output.stop()
        current = null
    }

    private fun drain() {
        if (current != null) return
        val next = waiting.minWithOrNull(compareBy<AlarmPlaybackRequest> { it.alertAt }
            .thenBy { it.occurrence.scheduledTime }.thenBy { it.token })
        if (next == null) { idle(); return }
        waiting.remove(next)
        current = next
        cancelTimeout = scheduleTimeout(AlertEscalation.BURST_MS) { finish() }
        output.start(next)
    }

    private fun finish() {
        cancelTimeout?.invoke()
        cancelTimeout = null
        current?.let { completed[it.token] = it.alertAt }
        output.stop() // Always stop audio AND vibration before another burst can start.
        current = null
        drain()
    }
}
