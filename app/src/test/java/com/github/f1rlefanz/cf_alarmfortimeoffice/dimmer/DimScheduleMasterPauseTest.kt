package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Master-Pause-Backstop des Dimmers ([DimScheduleUseCase.applyCurrentState]).
 *
 * Vorher pruefte weder [DimScheduleUseCase] noch `DndScheduleUseCase` irgendwo
 * `MasterPausePrefs.pausedNow()` - die Pause wurde nur EINMALIG von `MasterPauseUseCase.pause()`
 * durchgesetzt. Danach genuegte ein einziger Regler/Toggle im Dimmer-Tab, um mitten in der Pause
 * wieder zu dimmen und die rollende Tick-Kette neu zu armieren, obwohl die UI "pausiert" anzeigt:
 * jeder ViewModel-Setter ruft `enable()` ungegatet. Gleiche Fehlerklasse (und gleiche Loesung) wie
 * der zentrale Master-Pause-Backstop in `AlarmUseCase.syncAlarms()`.
 *
 * Nur `applyCurrentState()` ist hier pruefbar; `scheduleNextTransition()` faellt in dieser
 * Umgebung ueber `PendingIntent.getBroadcast()` (Android-Stub) - dessen Pause-Gate wird am Geraet
 * verifiziert.
 */
class DimScheduleMasterPauseTest {

    private suspend fun sut(
        paused: Boolean,
        prefs: DimOverlayPrefs,
        notifier: DimCorrectionNotifier,
        alarmUseCase: IAlarmUseCase,
    ): DimScheduleUseCase {
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(paused)
        val ruleUseCase = DimRuleUseCase(mock<DimRuleRepository>())
        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(Result.success(emptyList()))
        return DimScheduleUseCase(mock<Context>(), alarmUseCase, spanStore, keineFreienTage(), ruleUseCase, prefs, notifier, masterPausePrefs)
    }

    private suspend fun pausedPrefs(): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.strengthNow()).thenReturn(55)
        whenever(prefs.warmthNow()).thenReturn(40)
        return prefs
    }

    @Test
    fun `Bei Master-Pause wird das Overlay abgeschaltet, nicht angeschaltet`() = runTest {
        val prefs = pausedPrefs()
        val notifier = mock<DimCorrectionNotifier>()
        val alarmUseCase = mock<IAlarmUseCase>()

        sut(paused = true, prefs = prefs, notifier = notifier, alarmUseCase = alarmUseCase).applyCurrentState()

        verify(prefs).setActiveOverlay(false, 55, 40)
        verify(prefs, never()).setActiveOverlay(eq(true), any(), any())
        verify(notifier).cancel()
        verify(notifier, never()).show(any(), any(), any())
    }

    @Test
    fun `Bei Master-Pause wird der Alarm-Bestand gar nicht erst gelesen`() = runTest {
        // Der Backstop steht VOR jeder anderen Logik - keine Fenster-Berechnung, kein
        // Repository-/Kalender-Zugriff waehrend der Pause.
        val prefs = pausedPrefs()
        val alarmUseCase = mock<IAlarmUseCase>()

        sut(paused = true, prefs = prefs, notifier = mock(), alarmUseCase = alarmUseCase).applyCurrentState()

        verifyNoInteractions(alarmUseCase)
    }

    @Test
    fun `Ohne Master-Pause wird der Alarm-Bestand gelesen`() = runTest {
        // Gegenprobe: das Gate darf nicht dauerhaft blockieren. Ohne Pause laeuft die normale
        // Fenster-Berechnung an - hier ueber die seiteneffektfreie Vorschau geprueft, weil
        // applyCurrentState() danach den (in dieser Umgebung nicht stubbaren) Override-Mutex nutzt.
        val prefs = pausedPrefs()
        whenever(prefs.togglesNow()).thenReturn(
            DimOverlayPrefs.Toggles(wellnessEnabled = true, rulesEnabled = false, nightDefaultEnabled = false)
        )
        // Pflicht-Stub: `windDownMinutesNow()` ist eine SUSPEND-Funktion, ihr Rueckgabewert laeuft
        // damit ueber `Any?` - Mockitos Default ist `null`, NICHT die 0 eines echten Int. Ungestubbt
        // scheitert die Wellness-Fenster-Berechnung (`windDownMinutesNow() * MIN_MS`) an einer NPE.
        whenever(prefs.windDownMinutesNow()).thenReturn(120)
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.getAllAlarms()).thenReturn(Result.success(emptyList()))

        sut(paused = false, prefs = prefs, notifier = mock(), alarmUseCase = alarmUseCase).previewTimeline()

        verify(alarmUseCase).getAllAlarms()
    }
}
