package com.EDanaII.pillreminder

/** Fixed elapsed-hour intervals anchored to the original starting instant. */
object DoseSchedule {
    fun next(start: Long, intervalHours: Int, after: Long): Long {
        require(intervalHours > 0)
        if (start > after) return start
        val interval = Math.multiplyExact(intervalHours.toLong(), 3_600_000L)
        val elapsed = Math.subtractExact(after, start)
        val steps = Math.addExact(elapsed / interval, 1)
        return Math.addExact(start, Math.multiplyExact(steps, interval))
    }
}
