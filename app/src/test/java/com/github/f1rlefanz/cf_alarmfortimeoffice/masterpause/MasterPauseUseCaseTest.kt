package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit-Tests fuer [MasterPauseUseCase].
 *
 * Der Master-Pause-Schalter haengt acht Abhaengigkeiten an einem einzigen Aufruf zusammen
 * (siehe Klassenkommentar dort) - eine kuenftige Aenderung koennte stillschweigend einen der
 * pause()/resume()-Aufrufe verlieren (z.B. dndSchedule.disable() vergessen), ohne dass
 * irgendein Test das bemerkt. Diese Tests fixieren: JEDE injizierte Abhaengigkeit wird in
 * pause() bzw. resume() GENAU EINMAL angestossen.
 *
 * [AlarmMaintenanceService.cancelNext]/[AlarmMaintenanceService.scheduleNext] sind KEINE
 * injizierten Abhaengigkeiten, sondern direkte Aufrufe auf einer companion object - sie laufen
 * ueber den echten (durch den "isReturnDefaultValues"-Unit-Test-Modus aus build.gradle.kts
 * entschaerften) Android-Code gegen einen gemockten [Context]. Stellvertretend verifiziert wird
 * hier der [AlarmManager]-Aufruf, den dieser Pfad ausloest.
 */
class MasterPauseUseCaseTest {

    private class Fixture(
        val useCase: MasterPauseUseCase,
        val prefs: MasterPausePrefs,
        val alarmUseCase: IAlarmUseCase,
        val dimSchedule: DimScheduleUseCase,
        val dndSchedule: DndScheduleUseCase,
        val hueSmartScheduler: HueSmartScheduler,
        val calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
        val directBootAlarmStore: DirectBootAlarmStore,
        val alarmManager: AlarmManager
    )

    private fun buildFixture(): Fixture {
        val prefs = mock<MasterPausePrefs>()
        val alarmUseCase = mock<IAlarmUseCase>()
        val dimSchedule = mock<DimScheduleUseCase>()
        // DndScheduleUseCase.CONDITION_ID ist ein "...".toUri() Companion-Feld - das laedt beim
        // ERSTEN Zugriff auf die Klasse (auch nur zum Mocken) android.net.Uri.parse(), das im
        // Unit-Test-JVM ohne echtes Android ungemockt null liefert und an Kotlins eingebautem
        // Nicht-Null-Check von toUri() mit einer NullPointerException scheitert. Ein statischer
        // Mock von Uri.parse() waehrend der Klasseninitialisierung umgeht das, ohne Produktionscode
        // anzufassen.
        val dndSchedule = Mockito.mockStatic(Uri::class.java).use { staticUri ->
            staticUri.`when`<Uri> { Uri.parse(any()) }.thenReturn(mock())
            mock<DndScheduleUseCase>()
        }
        val hueSmartScheduler = mock<HueSmartScheduler>()
        val calendarPreAlarmRefreshScheduler = mock<CalendarPreAlarmRefreshScheduler>()
        val directBootAlarmStore = mock<DirectBootAlarmStore>()
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        // AlarmMaintenanceService.cancelNext/scheduleNext() casten das Ergebnis auf AlarmManager -
        // ohne diesen Stub crasht der echte (durchlaufende) Android-Code mit einer ClassCastException.
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        val useCase = MasterPauseUseCase(
            prefs = prefs,
            alarmUseCase = alarmUseCase,
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule,
            hueSmartScheduler = hueSmartScheduler,
            calendarPreAlarmRefreshScheduler = calendarPreAlarmRefreshScheduler,
            directBootAlarmStore = directBootAlarmStore,
            context = context
        )

        return Fixture(
            useCase = useCase,
            prefs = prefs,
            alarmUseCase = alarmUseCase,
            dimSchedule = dimSchedule,
            dndSchedule = dndSchedule,
            hueSmartScheduler = hueSmartScheduler,
            calendarPreAlarmRefreshScheduler = calendarPreAlarmRefreshScheduler,
            directBootAlarmStore = directBootAlarmStore,
            alarmManager = alarmManager
        )
    }

    @Test
    fun `pause - stoesst jede injizierte Abhaengigkeit genau einmal an`() = runTest {
        val f = buildFixture()

        f.useCase.pause()

        verify(f.prefs, times(1)).setPaused(true)
        verify(f.directBootAlarmStore, times(1)).savePaused(true)
        verify(f.alarmUseCase, times(1)).deleteAllAlarms()
        verify(f.dimSchedule, times(1)).disable()
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
        // Stellvertretend fuer AlarmMaintenanceService.cancelNext(context): genau ein Cancel auf
        // dem AlarmManager, den es sich ueber getSystemService(ALARM_SERVICE) besorgt.
        verify(f.alarmManager, times(1)).cancel(anyOrNull<PendingIntent>())
    }

    @Test
    fun `resume - stoesst jede injizierte Abhaengigkeit genau einmal an`() = runTest {
        val f = buildFixture()

        f.useCase.resume()

        verify(f.prefs, times(1)).setPaused(false)
        verify(f.directBootAlarmStore, times(1)).savePaused(false)
        verify(f.dimSchedule, times(1)).enable()
        verify(f.dndSchedule, times(1)).enable()
        verify(f.hueSmartScheduler, times(1)).initializeSmartScheduling()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).reschedule()
    }

    @Test
    fun `pause - eine fehlgeschlagene Abhaengigkeit blockiert die anderen NICHT`() = runTest {
        val f = buildFixture()
        whenever(f.dimSchedule.disable()).thenThrow(RuntimeException("boom"))

        f.useCase.pause()

        // dimSchedule.disable() selbst schlug fehl, aber alle NACHFOLGENDEN Schritte muessen trotzdem laufen.
        verify(f.dndSchedule, times(1)).disable()
        verify(f.hueSmartScheduler, times(1)).cleanup()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).cancelAll()
    }

    @Test
    fun `resume - eine fehlgeschlagene Abhaengigkeit blockiert die anderen NICHT`() = runTest {
        val f = buildFixture()
        whenever(f.dimSchedule.enable()).thenThrow(RuntimeException("boom"))

        f.useCase.resume()

        verify(f.dndSchedule, times(1)).enable()
        verify(f.hueSmartScheduler, times(1)).initializeSmartScheduling()
        verify(f.calendarPreAlarmRefreshScheduler, times(1)).reschedule()
    }
}
