package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.time.LocalDateTime
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Die Kalender-Nachlage der Boot-Recovery zaehlt nur, was wirklich armiert wurde.
 *
 * `scheduleSystemAlarm()` weist einen uebersprungenen Wecker per `Result.failure` ab, statt zu
 * werfen. Die Nachlage-Schleife verwarf dieses Ergebnis und zaehlte trotzdem - die
 * Recovery-Statistik meldete einen Wecker als wiederhergestellt, der im AlarmManager nicht steht.
 */
class BootReceiverKalenderNachlageTest {

    private val event = CalendarEvent(
        id = "ev-1",
        title = "Frueh",
        startTime = LocalDateTime.now().plusDays(1),
        endTime = LocalDateTime.now().plusDays(1).plusHours(8),
        calendarId = "cal-a"
    )

    private val nachgelegt = AlarmInfo(
        id = 1,
        shiftId = "frueh",
        shiftName = "Frueh",
        triggerTime = System.currentTimeMillis() + 86_400_000L,
        formattedTime = "05:00"
    )

    @Test
    fun `abgewiesener Wecker der Kalender-Nachlage zaehlt nicht als wiederhergestellt`() {
        val receiver = BootReceiver().apply {
            alarmUseCase = mock<IAlarmUseCase> {
                onBlocking { getAllAlarms() } doReturn Result.success(emptyList())
                onBlocking { syncAlarms(any(), any(), anyOrNull()) } doReturn Result.success(listOf(nachgelegt))
                onBlocking { scheduleSystemAlarm(any()) } doReturn
                    Result.failure(IllegalStateException("uebersprungen"))
            }
            calendarSelectionRepository = mock<CalendarSelectionRepository> {
                onBlocking { getCurrentSelectedCalendarIds() } doReturn Result.success(setOf("cal-a"))
            }
            calendarUseCase = mock<ICalendarUseCase> {
                onBlocking { getCalendarEventsWithStatus(any(), any()) } doReturn Result.success(
                    CalendarFetchOutcome(events = listOf(event), requestedCalendars = 1)
                )
            }
            shiftUseCase = mock<IShiftUseCase> {
                onBlocking { recognizeShiftsInEvents(any()) } doReturn
                    Result.failure(IllegalStateException("fuer diesen Test ohne Belang"))
                onBlocking { getCurrentShiftConfig() } doReturn
                    Result.success(ShiftConfig(autoAlarmEnabled = true))
            }
        }

        val bericht = runBlocking { receiver.performAlarmRecoveryPerReflexion() }

        assertTrue("Bericht: $bericht", bericht.startsWith("Restored 0 alarms"))
    }

    private suspend fun BootReceiver.performAlarmRecoveryPerReflexion(): String {
        val methode = BootReceiver::class.java
            .getDeclaredMethod("performAlarmRecovery", Continuation::class.java)
            .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn<Any?> { fortsetzung ->
            methode.invoke(this, fortsetzung)
        } as String
    }
}
