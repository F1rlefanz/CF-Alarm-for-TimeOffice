package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Pendant zu `DimScheduleTickChainTest` fuer die eigene DND-Tick-Kette
 * ([DndScheduleUseCase.fallbackTick], eigener Request-Code, bewusst unabhaengig vom Dimmer).
 *
 * Betroffener Realfall: nur "Waehrend der Dienstzeit" aktiv, eine Urlaubswoche ohne Alarme ->
 * Fensterliste leer -> der letzte Tick cancelte den Alarm. Der neue Dienstplan wurde danach von
 * Aufrufern synchronisiert, die DND nicht nacharmieren; "Waehrend der Dienstzeit" blieb bis zum
 * naechsten Reboot wirkungslos.
 *
 * Zweiter Teil: die HALB OFFENE Fenstergrenze ([DndScheduleUseCase.isActiveAt]) - dieselbe Regel wie
 * `DimWindowResolver.activeSpan`, hier aber eine eigene Funktion des DND-Pfads und deshalb hier
 * eigenstaendig abgesichert (vorher stand sie nur als Inline-Ausdruck im Tick, voellig ungetestet).
 *
 * Dritter Teil: der 15-Minuten-Retry muss auch dann greifen, wenn der Lesefehler des Alarm-Bestands
 * INNERHALB des Dimmers passiert (Modus 1 "Schlaf-Fenster folgt dem Dimmer") - dort kam er vorher
 * als ununterscheidbar leere Fensterliste an, und DND plante stattdessen den 6-Stunden-Keep-alive.
 */
class DndScheduleTickChainTest {

    private val now = 1_770_000_000_000L

    @Test
    fun `Ohne aktive Fenster-Quelle bleibt die Kette self-cleaning`() {
        assertNull(
            DndScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = false)
        )
    }

    @Test
    fun `Aktive Fenster-Quelle ohne Fenster bekommt einen Keep-alive-Tick`() {
        val next = DndScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = true)
        assertEquals(now + 6 * 60 * 60_000L, next)
    }

    @Test
    fun `Ein Lesefehler des Alarm-Bestands fuehrt zu einem kurzen Retry statt zum Kettenabbruch`() {
        val next = DndScheduleUseCase.fallbackTick(now, alarmReadFailed = true, anySourceEnabled = true)
        assertEquals(now + 15 * 60_000L, next)
    }

    // --- Halb offene Fenstergrenze (first <= now < last) ---

    @Test
    fun `Ein Tick exakt auf dem Fenster-Ende gilt NICHT mehr als aktiv`() {
        assertFalse(DndScheduleUseCase.isActiveAt(100L..200L, now = 200L))
    }

    @Test
    fun `Ein Tick exakt auf dem Fenster-Start gilt als aktiv`() {
        assertTrue(DndScheduleUseCase.isActiveAt(100L..200L, now = 100L))
    }

    @Test
    fun `Innerhalb des Fensters gilt es als aktiv, davor nicht`() {
        assertTrue(DndScheduleUseCase.isActiveAt(100L..200L, now = 150L))
        assertFalse(DndScheduleUseCase.isActiveAt(100L..200L, now = 99L))
    }

    // --- Lesefehler INNERHALB des Dimmers (Modus 1) ---

    private fun sut(dimSchedule: DimScheduleUseCase, prefs: DndPrefs): DndScheduleUseCase {
        val masterPausePrefs = mock<MasterPausePrefs>()
        return DndScheduleUseCase(
            mock<Context>(), mock<ShiftSpanStore>(), keineFreienTage(), dimSchedule, prefs, masterPausePrefs
        )
    }

    /** Nur Modus 1 an, keine Rufbereitschaft-Schichten - dann wird der eigene
     *  `getAllAlarms()`-Zweig gar nicht betreten. */
    private suspend fun followDimmerOnlyPrefs(): DndPrefs {
        val prefs = mock<DndPrefs>()
        whenever(prefs.togglesNow()).thenReturn(
            DndPrefs.Toggles(followDimmerEnabled = true, duringShiftEnabled = false)
        )
        whenever(prefs.onCallShiftsNow()).thenReturn(emptySet())
        return prefs
    }

    @Test
    fun `Lesefehler im Dimmer-Pfad ergibt den 15-Minuten-Retry, nicht den 6-Stunden-Keep-alive`() = runTest {
        val dimSchedule = mock<DimScheduleUseCase>()
        whenever(dimSchedule.previewTimelineWithStatus()).thenReturn(
            DimScheduleUseCase.TimelinePreview(intervals = emptyList(), alarmReadFailed = true)
        )

        val next = sut(dimSchedule, followDimmerOnlyPrefs()).computeNextTransition(now)

        assertEquals(
            "Nur Modus 1 aktiv: der Lesefehler passiert im Dimmer. Ohne dessen Status-Kanal " +
                "haette DND den Keep-alive geplant und waere bis zu 6 h ohne Nicht-stoeren geblieben, " +
                "obwohl der Dimmer nach 15 min wieder dimmt.",
            now + 15 * 60_000L,
            next
        )
    }

    @Test
    fun `Ohne Lesefehler bleibt es bei der leeren Vorschau beim Keep-alive`() = runTest {
        // Gegenprobe: eine wirklich leere Nacht (z. B. Nachtdienst-Unterdrueckung) darf NICHT als
        // Fehler gelten - "leere Fensterliste" ist bedeutungstragend, kein Defekt.
        val dimSchedule = mock<DimScheduleUseCase>()
        whenever(dimSchedule.previewTimelineWithStatus()).thenReturn(
            DimScheduleUseCase.TimelinePreview(intervals = emptyList(), alarmReadFailed = false)
        )

        val next = sut(dimSchedule, followDimmerOnlyPrefs()).computeNextTransition(now)

        assertEquals(now + 6 * 60 * 60_000L, next)
    }
}
