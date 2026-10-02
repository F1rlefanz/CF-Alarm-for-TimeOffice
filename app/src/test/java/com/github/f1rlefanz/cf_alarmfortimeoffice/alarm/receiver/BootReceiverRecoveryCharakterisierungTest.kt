package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Charakterisierung der CE-Recovery `BootReceiver.performCompleteSystemRecovery()` - aus Sicht
 * des Weckers das, was nach einem Neustart WIEDER stehen muss (bzw. unter Master-Pause gerade
 * NICHT): 6h-Wartungskette, Dimmer-, DND-, Pre-Alarm- und Rufbereitschafts-Kette.
 *
 * Laeuft echt: die Recovery startet in ihrem eigenen `recoveryScope` auf Dispatchers.IO und
 * wartet [BootReceiver]s 5 s Stabilitaetspause ab - deshalb Mockito-`timeout`. Die 6h-Kette
 * (Companion von AlarmMaintenanceService) laeuft ueber den echten Code gegen einen gemockten
 * [AlarmManager] (Vorbild: MasterPauseUseCaseTest). Bewusst KEINE Zaehlung der
 * scheduleNext()-Aufrufe: gezaehlt wird, DASS die Kette steht, nicht wie oft sie gestellt wurde.
 */
class BootReceiverRecoveryCharakterisierungTest {

    private class Fixture(
        val receiver: BootReceiver,
        val context: Context,
        val alarmManager: AlarmManager,
        // Die Nebenketten haengen seit #131 (G11-14) am echten ZeitkettenArmierer - die Mocks
        // dahinter sind dieselben, die Behauptungen also unveraendert.
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase,
        val calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
        val rufbereitschaftAbfrage: RufbereitschaftAbfrage
    )

    /** @param paused `null` = der Pause-Read wirft. */
    private fun fixture(paused: Boolean?): Fixture {
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        val r = BootReceiver()
        r.alarmUseCase = mock<IAlarmUseCase> {
            onBlocking { getAllAlarms() } doReturn Result.success(emptyList())
        }
        r.calendarUseCase = mock<ICalendarUseCase>()
        r.shiftUseCase = mock<IShiftUseCase>()
        r.calendarSelectionRepository = mock<CalendarSelectionRepository> {
            onBlocking { getCurrentSelectedCalendarIds() } doReturn Result.success(emptySet())
        }
        r.authDataStoreRepository = mock<IAuthDataStoreRepository> {
            onBlocking { isAuthenticated() } doReturn Result.success(false)
        }
        r.directBootAlarmStore = mock<DirectBootAlarmStore>()
        val dim = mock<DimScheduleUseCase>()
        val dnd = mock<DndScheduleUseCase>()
        val preAlarm = mock<CalendarPreAlarmRefreshScheduler>()
        val ruf = mock<RufbereitschaftAbfrage>()
        r.zeitkettenArmierer = ZeitkettenArmierer({ dim }, { dnd }, { ruf }, { preAlarm })
        r.masterPausePrefs = mock<MasterPausePrefs> {
            if (paused == null) {
                onBlocking { pausedNow() } doThrow IllegalStateException("DataStore")
            } else {
                onBlocking { pausedNow() } doReturn paused
            }
        }
        return Fixture(r, context, alarmManager, dim, dnd, preAlarm, ruf)
    }

    private fun starteRecovery(f: Fixture) {
        BootReceiver::class.java
            .getDeclaredMethod("performCompleteSystemRecovery", Context::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(f.receiver, f.context, "TEST")
    }

    @Test
    fun `Master-Pause aktiv - Recovery raeumt alle Ketten ab und legt keinen Wecker an`() {
        val f = fixture(paused = true)

        starteRecovery(f)

        val r = f.receiver
        verifyBlocking(f.dimSchedule, timeout(WARTEZEIT_MS)) { disable() }
        verifyBlocking(f.dndSchedule, timeout(WARTEZEIT_MS)) { disable() }
        verify(f.calendarPreAlarmRefreshScheduler, timeout(WARTEZEIT_MS)).cancelAll()
        verifyBlocking(f.rufbereitschaftAbfrage, timeout(WARTEZEIT_MS)) { cancel() }
        // 6h-Kette gekappt (regulaer + Wachhund), nirgends neu gestellt.
        verify(f.alarmManager, atLeastOnce()).cancel(anyOrNull<PendingIntent>())
        verify(f.alarmManager, never()).setExactAndAllowWhileIdle(any(), any(), anyOrNull())
        verifyBlocking(r.alarmUseCase, never()) { syncAlarms(any(), any(), anyOrNull()) }
        verifyBlocking(f.dimSchedule, never()) { enable() }
        verifyBlocking(f.dndSchedule, never()) { enable() }
    }

    @Test
    fun `nicht pausiert - Recovery plant 6h-Kette und alle Nebenketten neu`() {
        val f = fixture(paused = false)

        starteRecovery(f)

        assertKettenGeplant(f)
    }

    @Test
    fun `Master-Pause nicht lesbar - Recovery laeuft fail-safe wie nicht pausiert`() {
        val f = fixture(paused = null)

        starteRecovery(f)

        assertKettenGeplant(f)
    }

    private fun assertKettenGeplant(f: Fixture) {
        val r = f.receiver
        verifyBlocking(f.dimSchedule, timeout(WARTEZEIT_MS)) { enable() }
        verifyBlocking(f.dndSchedule, timeout(WARTEZEIT_MS)) { enable() }
        verifyBlocking(f.calendarPreAlarmRefreshScheduler, timeout(WARTEZEIT_MS)) { reschedule() }
        verifyBlocking(f.rufbereitschaftAbfrage, timeout(WARTEZEIT_MS)) { reschedule() }
        verify(f.alarmManager, atLeastOnce())
            .setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), any(), anyOrNull())
        verifyBlocking(f.dimSchedule, never()) { disable() }
        verifyBlocking(f.dndSchedule, never()) { disable() }
    }

    private companion object {
        const val WARTEZEIT_MS = 15_000L
    }
}
