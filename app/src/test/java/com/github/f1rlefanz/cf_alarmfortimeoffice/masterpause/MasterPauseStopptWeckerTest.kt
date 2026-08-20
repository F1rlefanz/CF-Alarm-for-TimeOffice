package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pruefrunde 8: `pause()` beendet einen gerade KLINGELNDEN Wecker.
 *
 * DER BEFUND: `pause()` raeumte ausschliesslich die Planung ab (Alarme loeschen, 6h-Kette,
 * Dimmer, DND, Hue, Pre-Alarm) und fasste den laufenden `AlarmSoundService` nicht an. Der Wecker
 * klingelte nach dem Pausieren weiter, waehrend die Oberflaeche "Hintergrunddienste pausiert"
 * zeigte — und seine Benachrichtigung blieb mitsamt Schlummer-Knopf stehen. Ein Druck darauf
 * armierte einen neuen Wecker mitten in der Pause; abgeraeumt haette ihn niemand mehr, weil die
 * Wartungskette in derselben Funktion gerade gekappt wurde.
 *
 * Der Schlummer-Pfad hat gegen den zweiten Teil inzwischen seinen eigenen Master-Pause-Backstop
 * (siehe `SchlummerEntscheidung`); dieser Test sichert den ERSTEN Teil: die Pause macht still.
 *
 * GRENZE DIESES TESTS: Im JVM-Unit-Test ist `android.content.Intent` ein Stub
 * (`isReturnDefaultValues`), die Action des Intents laesst sich deshalb nicht auslesen. Geprueft
 * wird, DASS pause() genau einen Dienst-Start ausloest und resume() keinen — ohne den Fix gibt es
 * gar keinen. Dass der Intent `ACTION_STOP_ALARM` traegt, haelt der Kommentar an der Aufrufstelle
 * fest und ist am Geraet zu sehen (Wecker klingelt, Pause einschalten, Ton muss aufhoeren).
 */
class MasterPauseStopptWeckerTest {

    private class Fixture(
        val useCase: MasterPauseUseCase,
        val context: Context,
        val alarmManager: AlarmManager
    )

    private fun buildFixture(): Fixture {
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        // AlarmMaintenanceService.cancelNext/scheduleNext() casten das Ergebnis auf AlarmManager -
        // ohne diesen Stub crasht der echte (durchlaufende) Android-Code mit einer ClassCastException.
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        val useCase = MasterPauseUseCase(
            prefs = mock(),
            alarmUseCase = mock<IAlarmUseCase>(),
            dimSchedule = mock<DimScheduleUseCase>(),
            dndSchedule = mock<DndScheduleUseCase>(),
            hueSmartScheduler = mock<HueSmartScheduler>(),
            calendarPreAlarmRefreshScheduler = mock<CalendarPreAlarmRefreshScheduler>(),
            directBootAlarmStore = mock<DirectBootAlarmStore>(),
            context = context
        )
        return Fixture(useCase = useCase, context = context, alarmManager = alarmManager)
    }

    @Test
    fun `pause beendet einen laufenden Wecker`() = runTest {
        val f = buildFixture()

        f.useCase.pause()

        verify(f.context, times(1)).startService(anyOrNull<Intent>())
    }

    @Test
    fun `pause laeuft weiter, wenn der Stopp des Weckers abgelehnt wird`() = runTest {
        // Ab Android 8 lehnt das System startService() aus dem Hintergrund ab. Das darf die Pause
        // nicht zerreissen: alle NACHFOLGENDEN Schritte (Alarme loeschen, Ketten kappen) muessen
        // trotzdem laufen, sonst zeigt die App "pausiert", waehrend alles weiterlaeuft.
        val f = buildFixture()
        whenever(f.context.startService(anyOrNull())).thenThrow(IllegalStateException("background"))

        f.useCase.pause()

        // Stellvertretend fuer AlarmMaintenanceService.cancelNext(context): zwei Cancel auf dem
        // AlarmManager (regulaerer 6h-Slot UND Wiederanlauf-Wachhund).
        verify(f.alarmManager, times(2)).cancel(anyOrNull<android.app.PendingIntent>())
    }

    @Test
    fun `resume startet keinen Weckton`() = runTest {
        // resume() baut Ketten wieder auf - einen WECKER darf es dabei nicht anwerfen.
        // AlarmMaintenanceService.start() laeuft ueber startForegroundService(), nicht hierueber.
        val f = buildFixture()

        f.useCase.resume()

        verify(f.context, never()).startService(anyOrNull<Intent>())
    }
}
