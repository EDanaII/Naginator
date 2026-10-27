package com.EDanaII.pillreminder

import org.json.JSONObject

/** One central policy, including the non-escalating (initial-level) behavior. */
object AlertEscalation {
    const val BURST_MS = 30_000L
    data class Level(val gain: Float, val vibration: List<Long>)
    val levels = listOf(
        Level(.25f, listOf(0L, 1_000L, 9_000L)),
        Level(.50f, listOf(0L, 2_000L, 3_000L)),
        Level(.75f, listOf(0L, 3_000L, 1_000L)),
        Level(1f, listOf(0L, BURST_MS))
    )
    fun level(index: Int, enabled: Boolean = true): Level = levels[if (enabled) index.coerceIn(0, 3) else 0]
    fun next(index: Int, enabled: Boolean): Int = if (enabled) (index + 1).coerceIn(0, 3) else 0
    fun nextNag(now: Long, minutes: Int): Long = Math.addExact(now, Math.multiplyExact(minutes.toLong(), 60_000L))
    fun finiteVibration(index: Int, enabled: Boolean = true): LongArray {
        val cycle = level(index, enabled).vibration
        val result = mutableListOf(0L)
        var remaining = BURST_MS
        var phase = 1
        while (remaining > 0) {
            val duration = minOf(cycle[phase], remaining)
            result.add(duration)
            remaining -= duration
            phase = if (phase + 1 == cycle.size) 1 else phase + 1
        }
        return result.toLongArray()
    }
}

/** Flat JSON remains readable by the previous occurrence decoder during migration. */
internal data class NagState(val occurrence: AlarmOccurrence, val level: Int = 0,
    val nextNagTime: Long = 0, val lastAlertTime: Long = 0) {
    fun encode(): String = JSONObject(AlarmCoordinator.encode(occurrence)).put("level", level)
        .put("nextNag", nextNagTime).put("lastAlert", lastAlertTime).toString()

    companion object {
        fun decode(value: String): NagState? {
            val occurrence = AlarmCoordinator.decode(value) ?: return null
            return try {
                val json = JSONObject(value)
                NagState(occurrence, json.optInt("level", 0).coerceIn(0, 3),
                    json.optLong("nextNag", 0), json.optLong("lastAlert", 0))
            } catch (_: org.json.JSONException) { null }
        }
    }
}
