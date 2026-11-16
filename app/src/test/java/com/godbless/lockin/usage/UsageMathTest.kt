package com.godbless.lockin.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageMathTest {
    private val min = 60_000L
    private fun e(pkg: String, type: Int, t: Long, cls: String = "Main") = UsageEvent(pkg, cls, type, t)
    private val up = UsageEventType.Resumed
    private val down = UsageEventType.Paused

    @Test
    fun oneAppOpenedAndClosed() {
        val r = foregroundSeconds(listOf(e("a", up, 0), e("a", down, 10 * min)), 0, 60 * min)
        assertEquals(600, r["a"])
    }

    @Test
    fun switchingAppsSplitsTheTime() {
        val events = listOf(e("a", up, 0), e("a", down, 5 * min), e("b", up, 5 * min), e("b", down, 8 * min))
        val r = foregroundSeconds(events, 0, 60 * min)
        assertEquals(300, r["a"])
        assertEquals(180, r["b"])
    }

    @Test
    fun aLostPausedEventDoesNotRunOn() {
        // "a" is never paused, but "b" comes to the front at minute 4, so "a" stops there.
        val events = listOf(e("a", up, 0), e("b", up, 4 * min), e("b", down, 6 * min))
        val r = foregroundSeconds(events, 0, 600 * min)
        assertEquals(240, r["a"])
        assertEquals(120, r["b"])
    }

    @Test
    fun screenOffClosesEverything() {
        val events = listOf(e("a", up, 0), e("android", UsageEventType.ScreenOff, 3 * min))
        assertEquals(180, foregroundSeconds(events, 0, 60 * min)["a"])
    }

    @Test
    fun stillOpenCountsUntilNow() {
        val r = foregroundSeconds(listOf(e("a", up, 2 * min)), 0, 5 * min)
        assertEquals(180, r["a"])
    }

    @Test
    fun timeIsClippedToTheWindow() {
        // Open from minute 0 to 10, but the window is minutes 4 to 8.
        val r = foregroundSeconds(listOf(e("a", up, 0), e("a", down, 10 * min)), 4 * min, 8 * min)
        assertEquals(240, r["a"])
    }

    @Test
    fun eventsAfterTheWindowAreIgnored() {
        val r = foregroundSeconds(listOf(e("a", up, 10 * min), e("a", down, 20 * min)), 0, 5 * min)
        assertNull(r["a"])
    }

    @Test
    fun twoScreensOfOneAppCountOnce() {
        val events = listOf(e("a", up, 0, "One"), e("a", up, 1 * min, "Two"), e("a", down, 2 * min, "One"), e("a", down, 3 * min, "Two"))
        assertEquals(180, foregroundSeconds(events, 0, 60 * min)["a"])
    }

    @Test
    fun outOfOrderEventsAreSorted() {
        val r = foregroundSeconds(listOf(e("a", down, 10 * min), e("a", up, 0)), 0, 60 * min)
        assertEquals(600, r["a"])
    }

    @Test
    fun tinyUseIsLeftOut() {
        val r = foregroundSeconds(listOf(e("a", up, 0), e("a", down, 400)), 0, min)
        assertNull(r["a"])
    }

    @Test
    fun durationWording() {
        assertEquals("under a minute", durationText(30))
        assertEquals("12 m", durationText(12 * 60 + 40))
        assertEquals("1 h 05 m", durationText(65 * 60))
        assertEquals("2 h 00 m", durationText(120 * 60))
    }
}
