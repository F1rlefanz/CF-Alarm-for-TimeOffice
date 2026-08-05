package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPreAlarmRefreshSchedulerTest {

    private val now = 1_000_000_000L

    @Test
    fun `selectUpcomingTriggerTimes deckelt auf maxScheduled und behaelt die FRUEHESTEN`() {
        val triggerTimes = (0 until 15).map { now + TimeUnit.HOURS.toMillis(1) * (it + 1) }

        val result = CalendarPreAlarmRefreshScheduler.selectUpcomingTriggerTimes(
            triggerTimes = triggerTimes,
            now = now,
            maxLookaheadDays = 14L,
            maxScheduled = 10
        )

        assertEquals(10, result.size)
        assertEquals(triggerTimes.take(10), result)
    }

    @Test
    fun `selectUpcomingTriggerTimes dedupliziert identische Weckzeiten`() {
        val triggerTime = now + TimeUnit.HOURS.toMillis(5)
        val triggerTimes = listOf(triggerTime, triggerTime, triggerTime)

        val result = CalendarPreAlarmRefreshScheduler.selectUpcomingTriggerTimes(
            triggerTimes = triggerTimes,
            now = now,
            maxLookaheadDays = 14L,
            maxScheduled = 10
        )

        assertEquals(listOf(triggerTime), result)
    }

    @Test
    fun `selectUpcomingTriggerTimes schliesst Zeiten ausserhalb des Lookahead-Fensters aus`() {
        val maxLookaheadDays = 14L
        val inPast = now - TimeUnit.HOURS.toMillis(1)
        val farBeyond = now + TimeUnit.DAYS.toMillis(maxLookaheadDays) + TimeUnit.HOURS.toMillis(1)
        val inWindow = now + TimeUnit.HOURS.toMillis(12)

        val result = CalendarPreAlarmRefreshScheduler.selectUpcomingTriggerTimes(
            triggerTimes = listOf(inPast, farBeyond, inWindow),
            now = now,
            maxLookaheadDays = maxLookaheadDays,
            maxScheduled = 10
        )

        assertEquals(listOf(inWindow), result)
    }

    @Test
    fun `isWithinLeadTime erkennt einen Alarm bereits innerhalb des Vorlaufs`() {
        val triggerTime = now + TimeUnit.HOURS.toMillis(2)
        val leadMillis = TimeUnit.HOURS.toMillis(3)

        assertTrue(CalendarPreAlarmRefreshScheduler.isWithinLeadTime(triggerTime, now, leadMillis))
    }

    @Test
    fun `isWithinLeadTime laesst einen Alarm ausserhalb des Vorlaufs unberuehrt`() {
        val triggerTime = now + TimeUnit.HOURS.toMillis(10)
        val leadMillis = TimeUnit.HOURS.toMillis(3)

        assertFalse(CalendarPreAlarmRefreshScheduler.isWithinLeadTime(triggerTime, now, leadMillis))
    }
}
