package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * "Waehrend der Dienstzeit" bezieht seine Fenster seit v1.25.2 aus den [ShiftSpan]s und NICHT mehr
 * aus dem Alarm-Bestand.
 *
 * Der Grund ist am Geraet gemessen (14.08.2026, Emulator): am 20.08. um 08:00, mitten in der
 * Fruehschicht (Kalender-Termin 06:00-14:12), war `zen_mode=0` und die Zen-Regel `STATE_FALSE` -
 * obwohl der Trigger eingeschaltet war. Der Alarm um 05:30 hatte geklingelt, der naechste Sync
 * hatte ihn geraeumt, und mit ihm verschwand das Dienstzeit-Fenster der laufenden Schicht. Ein
 * Alarm ist ein Weckzeitpunkt, eine Schichtspanne ist ein Dienst - erst diese Trennung macht den
 * Trigger belastbar.
 */
class DndShiftSpanSourceTest {

    private val now = 1_770_000_000_000L
    private val hour = 60 * 60 * 1000L

    private suspend fun duringShiftPrefs(onCall: Set<String> = emptySet()): DndPrefs {
        val prefs = mock<DndPrefs>()
        whenever(prefs.togglesNow()).thenReturn(
            DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)
        )
        whenever(prefs.onCallShiftsNow()).thenReturn(onCall)
        whenever(prefs.shiftExcludedShiftsNow()).thenReturn(emptySet())
        whenever(prefs.onCallCutoffMinutesNow()).thenReturn(DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN)
        return prefs
    }

    private fun sut(prefs: DndPrefs, spans: Result<List<ShiftSpan>>): DndScheduleUseCase {
        val spanStore = mock<ShiftSpanStore>()
        kotlinx.coroutines.runBlocking { whenever(spanStore.spansNow()).thenReturn(spans) }
        return DndScheduleUseCase(
            mock<Context>(), spanStore, keineFreienTage(), mock<DimScheduleUseCase>(), prefs, mock<MasterPausePrefs>()
        )
    }

    @Test
    fun `Eine laufende Schicht erzeugt ein Fenster, obwohl gar kein Alarm mehr existiert`() = runTest {
        // Die Weckzeit liegt VOR jetzt (der Wecker hat geklingelt, der Alarm ist geraeumt) - die
        // Schicht laeuft aber noch zwei Stunden. Genau der Fall, der vorher durchfiel.
        val span = ShiftSpan(
            shiftName = "Fruehschicht",
            startTime = now - 2 * hour,
            endTime = now + 2 * hour,
            alarmTriggerTime = now - 3 * hour
        )

        val next = sut(duringShiftPrefs(), Result.success(listOf(span))).computeNextTransition(now)

        assertEquals(
            "Naechster Wechsel ist das Schichtende - es gibt also ein aktives Fenster, " +
                "obwohl der Alarm-Bestand leer ist.",
            now + 2 * hour,
            next
        )
    }

    @Test
    fun `Ohne Schichtspannen bleibt es beim Keep-alive statt beim Retry`() = runTest {
        // Gegenprobe: "keine Schicht" ist eine gueltige Aussage (Urlaub), kein Lesefehler.
        val next = sut(duringShiftPrefs(), Result.success(emptyList())).computeNextTransition(now)

        assertEquals(now + 6 * 60 * 60_000L, next)
    }

    @Test
    fun `Ein Lesefehler der Schichtspannen ergibt den kurzen Retry, nicht den Keep-alive`() = runTest {
        // Der Unterschied, der dem alten Alarm-Pfad an der Dimmer-DND-Grenze gefehlt hat: ein
        // Fehlschlag darf nicht als "heute kein Dienst" durchgehen, sonst bleibt DND bis zu sechs
        // Stunden aus, obwohl der naechste Versuch schon in 15 Minuten Erfolg haette.
        val next = sut(duringShiftPrefs(), Result.failure(RuntimeException("Store kaputt")))
            .computeNextTransition(now)

        assertEquals(now + 15 * 60_000L, next)
    }

    @Test
    fun `Der Rufbereitschaft-Cutoff klippt die Spanne weiterhin`() = runTest {
        // Regressionsschutz fuer die am Geraet bereits gruene Messung (21.08. 04:00 an / 05:30 aus):
        // der Cutoff arbeitet jetzt auf Spannen statt auf Alarmen und muss identisch wirken.
        val dayStart = 1_770_000_000_000L / (24 * hour) * (24 * hour) // Mitternacht UTC
        val span = ShiftSpan(
            shiftName = "AD1",
            startTime = dayStart,
            endTime = dayStart + 24 * hour,
            alarmTriggerTime = dayStart + 6 * hour
        )

        val next = sut(duringShiftPrefs(onCall = setOf("AD1")), Result.success(listOf(span)))
            .computeNextTransition(dayStart + hour)

        assertEquals(
            "Das Fenster darf nicht bis 24:00 laufen - der Cutoff kappt es deutlich frueher.",
            true,
            next != null && next < dayStart + 24 * hour
        )
    }
}
