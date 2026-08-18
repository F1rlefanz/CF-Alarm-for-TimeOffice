package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [DimScheduleUseCase.previewTimelineWithStatus] - der Status-Kanal, ueber den ein Lesefehler des
 * Alarm-Bestands die Grenze vom Dimmer zu DND ueberquert.
 *
 * WARUM eigenstaendig getestet: die Fenster-Berechnung des Dimmers verflacht einen transienten
 * Lesefehler bewusst auf "keine Fenster" (fail-open, es wird dann NICHT gedimmt). Fuer den
 * Aufrufer ist das damit nicht mehr von "heute gibt es einfach kein Fenster" zu unterscheiden - und
 * genau diese Unterscheidung braucht DND-Modus 1 ("Schlaf-Fenster folgt dem Dimmer"), um seinen
 * 15-Minuten-Retry statt des 6-Stunden-Keep-alive zu planen. Vorher blieb DND nach einem
 * Lesefehler bis zu 6 h aus, obwohl sich der Dimmer selbst nach 15 min erholte und die Nacht dimmte.
 *
 * Die Einbahnstrasse bleibt gewahrt: der Dimmer liefert nur einen Wert, er weiss nichts von DND.
 */
class DimSchedulePreviewStatusTest {

    private suspend fun sut(alarms: Result<List<AlarmInfo>>, wellnessEnabled: Boolean = true): DimScheduleUseCase {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.togglesNow()).thenReturn(
            DimOverlayPrefs.Toggles(
                wellnessEnabled = wellnessEnabled,
                rulesEnabled = false,
                nightDefaultEnabled = false
            )
        )
        // Suspend-Getter liefern ungestubbt `null` (Mockito-Default ueber Any?) und wuerden die
        // Wellness-Rechnung an einer NPE scheitern lassen.
        whenever(prefs.windDownMinutesNow()).thenReturn(120)
        whenever(prefs.strengthNow()).thenReturn(55)
        whenever(prefs.warmthNow()).thenReturn(40)

        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.getAllAlarms()).thenReturn(alarms)
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(false)

        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(Result.success(emptyList()))
        return DimScheduleUseCase(
            mock<Context>(),
            alarmUseCase,
            spanStore,
            DimRuleUseCase(mock<DimRuleRepository>()),
            prefs,
            mock<DimCorrectionNotifier>(),
            masterPausePrefs
        )
    }

    @Test
    fun `Ein Lesefehler des Alarm-Bestands wird als alarmReadFailed nach aussen gemeldet`() = runTest {
        val preview = sut(Result.failure(RuntimeException("Kalender/Token kaputt"))).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertTrue(
            "Ohne dieses Flag ist der Lesefehler von einer legitim leeren Nacht nicht zu " +
                "unterscheiden - DND-Modus 1 plant dann den 6-Stunden-Keep-alive statt des Retrys",
            preview.alarmReadFailed
        )
    }

    @Test
    fun `Eine legitim leere Fensterliste ist KEIN Lesefehler`() = runTest {
        // Alle Quellen aus: leer, aber fehlerfrei. "Leere Fensterliste" ist bedeutungstragend
        // (z. B. Nachtdienst-Unterdrueckung) und darf keinen Retry-Tick ausloesen.
        val preview = sut(Result.success(emptyList()), wellnessEnabled = false).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertFalse(preview.alarmReadFailed)
    }
}
