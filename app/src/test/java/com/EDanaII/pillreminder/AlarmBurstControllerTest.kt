package com.EDanaII.pillreminder

import org.junit.Assert.*
import org.junit.Test

class AlarmBurstControllerTest {
    private fun request(token: String, at: Long = 100, level: Int = 0) = AlarmPlaybackRequest(
        AlarmOccurrence(1, at, token, false, "revision"), at, level, null, true, true, true)

    private class Harness {
        val events = mutableListOf<String>()
        var now = 0L
        var deadline: Long? = null
        var callback: (() -> Unit)? = null
        val controller = AlarmBurstController(object : AlarmBurstController.Output {
            override fun start(request: AlarmPlaybackRequest) { events.add("start:${request.token}:${request.level}") }
            override fun stop() { events.add("stop") }
        }, { delay, action ->
            deadline = now + delay
            callback = action
            val cancel: () -> Unit = { callback = null; deadline = null }
            cancel
        }, { events.add("idle") })
        fun advance(milliseconds: Long) {
            now += milliseconds
            if (deadline?.let { now >= it } == true) callback?.invoke()
        }
    }

    @Test fun centralPolicyHasFourExactGainsAndPatternsAndCeiling() {
        assertEquals(listOf(.25f, .5f, .75f, 1f), AlertEscalation.levels.map { it.gain })
        assertEquals(listOf(0L, 1_000L, 9_000L), AlertEscalation.level(0).vibration)
        assertEquals(listOf(0L, 2_000L, 3_000L), AlertEscalation.level(1).vibration)
        assertEquals(listOf(0L, 3_000L, 1_000L), AlertEscalation.level(2).vibration)
        assertEquals(listOf(0L, 30_000L), AlertEscalation.level(3).vibration)
        assertEquals(3, AlertEscalation.next(3, true))
        assertEquals(AlertEscalation.level(0), AlertEscalation.level(3, false))
    }

    @Test fun stopsAtThirtySecondsAndDoesNotRestartCompletedBurst() {
        val h = Harness()
        h.controller.enqueue(request("one"))
        h.advance(29_999)
        assertNotNull(h.controller.current)
        assertEquals(listOf("start:one:0"), h.events)
        h.advance(1)
        assertNull(h.controller.current)
        assertEquals(listOf("start:one:0", "stop", "idle"), h.events)
        h.controller.enqueue(request("one"))
        assertNull(h.controller.current)
    }

    @Test fun overlapsAreSerializedAndDismissOnlyStopsRelevantOccurrence() {
        val h = Harness()
        h.controller.enqueue(request("one"))
        h.controller.enqueue(request("two", 200))
        h.controller.enqueue(request("three", 300))
        assertEquals("one", h.controller.current!!.token)
        h.controller.dismiss("two")
        h.controller.dismiss("one")
        assertEquals("three", h.controller.current!!.token)
        assertEquals(listOf("start:one:0", "stop", "start:three:0"), h.events)
        h.advance(30_000)
        assertNull(h.controller.current)
        h.controller.enqueue(request("two", 400))
        assertNull(h.controller.current) // Dismissal is permanent for this token.
    }

    @Test fun waitingBurstKeepsLatestLevelWithoutDiscardingOtherOccurrences() {
        val h = Harness()
        h.controller.enqueue(request("one"))
        h.controller.enqueue(request("two", 200, 1))
        h.controller.enqueue(request("two", 300, 2))
        h.controller.enqueue(request("three", 250))
        h.advance(30_000)
        assertEquals("three", h.controller.current!!.token)
        h.advance(30_000)
        assertEquals("two", h.controller.current!!.token)
        assertEquals(2, h.controller.current!!.level)
        h.controller.shutdown()
        assertNull(h.controller.current)
        assertNull(h.callback)
    }

    @Test fun disablingScheduleDropsAllItsWaitingBurstsBeforeSelectingAnother() {
        val h = Harness()
        h.controller.enqueue(request("one"))
        h.controller.enqueue(request("two", 200))
        val other = request("other", 300)
        h.controller.enqueue(other.copy(occurrence = other.occurrence.copy(reminderId = 2)))
        h.controller.dismissSchedule(1)
        assertEquals("other", h.controller.current!!.token)
        assertEquals(listOf("start:one:0", "stop", "start:other:0"), h.events)
        h.controller.enqueue(request("two", 400))
        h.advance(30_000)
        assertNull(h.controller.current)
    }

    @Test fun cancellationRejectsRequestThatFinishesLoadingAfterTheScheduleWasDisabled() {
        val h = Harness()
        h.controller.dismissTokens(listOf("loading-one", "loading-two"))
        h.controller.enqueue(request("loading-one"))
        h.controller.enqueue(request("loading-two"))
        assertNull(h.controller.current)
        assertTrue(h.events.none { it.startsWith("start:") })
    }
}
