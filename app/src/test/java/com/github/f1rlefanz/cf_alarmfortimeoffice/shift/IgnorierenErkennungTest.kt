package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Umbau E1 (#167): Termine, die NUR ein Typ der Kategorie Ignorieren trifft, ergeben keine Schicht
 * (also keinen Wecker und keine Schichtspanne) und landen getrennt in [Erkennung.ignorierteTermine] -
 * die spaetere Zeile „Ohne Kalendereintrag“ zaehlt solche Tage als leer.
 *
 * Die Reihenfolge der Definitionen ist in jedem Test ABSICHTLICH ungünstig: der Ignorieren-Typ steht
 * VOR dem Dienst-Typ. Unter der alten Regel „erste passende Definition gewinnt“ wuerde er sonst nie
 * gefragt, und der Test waere auch ohne die Aenderung gruen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IgnorierenErkennungTest {

    private class FesteKonfiguration(private val config: ShiftConfig) : IShiftConfigRepository {
        var loadCount = 0
            private set

        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)

        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> {
            loadCount++
            return Result.success(config)
        }

        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
    }

    private fun typ(name: String, muster: String, kategorie: ShiftKategorie, enabled: Boolean = true) =
        ShiftDefinition(
            id = name.lowercase(),
            name = name,
            keywords = listOf(muster),
            alarmTime = LocalTime.of(5, 0),
            isEnabled = enabled
        ).mitKategorie(kategorie)

    private fun termin(titel: String, day: Int = 10) = CalendarEvent(
        id = "e$day-$titel",
        title = titel,
        startTime = LocalDateTime.of(2026, 10, day, 6, 0),
        endTime = LocalDateTime.of(2026, 10, day, 14, 0),
        calendarId = "cal1"
    )

    private fun TestScope.engine(vararg typen: ShiftDefinition) =
        engineMit(FesteKonfiguration(ShiftConfig(autoAlarmEnabled = true, definitions = typen.toList())))

    private fun TestScope.engineMit(repo: FesteKonfiguration) =
        ShiftRecognitionEngine(repo, UnconfinedTestDispatcher(testScheduler))

    private val tb = typ("Teambesprechung", "TB", ShiftKategorie.IGNORIEREN)
    private val frueh = typ("Frueh", "F", ShiftKategorie.DIENST)

    @Test
    fun `nur ein Ignorieren-Typ trifft - keine Schicht, Eintrag in der Ignoriert-Liste`() = runTest {
        val engine = engine(tb, frueh)
        val nurTb = termin("TB")

        val erkennung = engine.erkenne(listOf(nurTb))

        assertTrue("Ein ignorierter Termin ist keine Schicht", erkennung.schichten.isEmpty())
        assertEquals(listOf(nurTb), erkennung.ignorierteTermine)
        assertTrue("Auch kein Krank-Termin", erkennung.krankTermine.isEmpty())
        assertTrue(
            "getAllMatchingShifts (Dimmer, Hue, Wartung) darf ihn ebenfalls nicht sehen",
            engine.getAllMatchingShifts(listOf(nurTb)).isEmpty()
        )
    }

    @Test
    fun `Ignorieren verdraengt nie einen echten Treffer - F TB ist Schicht F`() = runTest {
        val engine = engine(tb, frueh)
        val beides = termin("F TB")
        val nurTb = termin("TB", day = 11)

        val erkennung = engine.erkenne(listOf(beides, nurTb))

        assertEquals(listOf(beides), erkennung.schichten.map { it.calendarEvent })
        assertEquals("Frueh", erkennung.schichten.single().shiftDefinition.name)
        assertEquals("Ein Termin mit echtem Treffer ist nicht ignoriert", listOf(nurTb), erkennung.ignorierteTermine)
    }

    @Test
    fun `Krank behaelt seinen Vorrang vor Ignorieren`() = runTest {
        val engine = engine(tb, typ("Krank", "K", ShiftKategorie.KRANK))
        val beides = termin("TB K")

        val erkennung = engine.erkenne(listOf(beides))

        assertEquals(listOf(beides), erkennung.krankTermine)
        assertTrue(erkennung.ignorierteTermine.isEmpty())
        assertTrue(erkennung.schichten.isEmpty())
    }

    /** Wie bei Krank: ausgeschaltet heisst „wird nicht erkannt“ - der Termin ist dann weder Schicht noch ignoriert. */
    @Test
    fun `ausgeschalteter Ignorieren-Typ ignoriert nichts`() = runTest {
        val engine = engine(typ("Teambesprechung", "TB", ShiftKategorie.IGNORIEREN, enabled = false), frueh)

        val erkennung = engine.erkenne(listOf(termin("TB"), termin("F TB", day = 11)))

        assertTrue(erkennung.ignorierteTermine.isEmpty())
        assertEquals(listOf("F TB"), erkennung.schichten.map { it.calendarEvent.title })
    }

    @Test
    fun `Ignoriert-Liste kommt aus demselben gecachten Lauf und folgt der Invalidierung`() = runTest {
        val repo = FesteKonfiguration(ShiftConfig(autoAlarmEnabled = true, definitions = listOf(tb, frueh)))
        val engine = engineMit(repo)
        val termine = listOf(termin("F"), termin("TB", day = 11))

        val schichten = engine.getAllMatchingShifts(termine)
        val ignoriert = engine.erkenne(termine).ignorierteTermine

        assertEquals(1, schichten.size)
        assertEquals(1, ignoriert.size)
        assertEquals("Beide Listen muessen aus EINEM Konfigurations-Read stammen", 1, repo.loadCount)

        engine.clearRecognitionCache()
        assertEquals(1, engine.erkenne(termine).ignorierteTermine.size)
        assertEquals("Nach clearRecognitionCache() entsteht auch die Ignoriert-Liste neu", 2, repo.loadCount)
    }
}
