package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Echte Unit-Tests für die Wecker-Kernlogik.
 *
 * Diese Tests prüfen die PRODUKTIVE Zeitberechnung in [ShiftRecognitionEngine]
 * (calculateAlarmTime inkl. Mitternachts-/Vortags-Logik) sowie die
 * Schlüsselwort-Erkennung – über die öffentliche API getAllMatchingShifts,
 * nicht über einen Mock/Stub. Ein Refactor der echten Logik, der einen dieser
 * Fälle bricht, wird dadurch sichtbar.
 */
class AlarmSchedulerTest {

    /** Minimaler Fake statt Mockito – liefert eine feste ShiftConfig. */
    private class FakeShiftConfigRepository(
        private val config: ShiftConfig
    ) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun resetToDefaults(): Result<Unit> = Result.success(Unit)
        override suspend fun hasValidConfig(): Result<Boolean> =
            Result.success(config.definitions.isNotEmpty())
    }

    private fun engineWith(vararg definitions: ShiftDefinition): ShiftRecognitionEngine =
        ShiftRecognitionEngine(FakeShiftConfigRepository(ShiftConfig(definitions = definitions.toList())))

    private fun event(title: String, start: LocalDateTime) = CalendarEvent(
        id = "$title-$start",
        title = title,
        startTime = start,
        endTime = start.plusHours(8),
        calendarId = "test"
    )

    private fun shift(name: String, keywords: List<String>, alarm: LocalTime) = ShiftDefinition(
        id = name,
        name = name,
        keywords = keywords,
        alarmTime = alarm
    )

    @Test
    fun `Alarm liegt am selben Tag wenn Weckzeit vor Schichtbeginn`() = runTest {
        // Frühschicht: Weckzeit 05:30, Schicht startet 06:00 -> Wecker 05:30 am selben Tag
        val engine = engineWith(shift("Früh", listOf("F"), LocalTime.of(5, 30)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("F", LocalDateTime.of(2025, 1, 20, 6, 0)))
        )

        assertEquals(1, matches.size)
        assertEquals(LocalDateTime.of(2025, 1, 20, 5, 30), matches[0].calculatedAlarmTime)
    }

    @Test
    fun `Alarm liegt am Vortag wenn Weckzeit nach Schichtbeginn (Mitternachts-Fall)`() = runTest {
        // Schicht startet 00:30, Weckzeit 23:30 -> Wecker am VORTAG 23:30
        val engine = engineWith(shift("Nacht", listOf("N"), LocalTime.of(23, 30)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("N", LocalDateTime.of(2025, 1, 20, 0, 30)))
        )

        assertEquals(1, matches.size)
        assertEquals(LocalDateTime.of(2025, 1, 19, 23, 30), matches[0].calculatedAlarmTime)
    }

    @Test
    fun `Weckzeit knapp nach Schichtbeginn bleibt am selben Tag (keine Vortag-Falle)`() = runTest {
        // Schicht startet 06:00, Weckzeit 06:05 (5min danach) -> KEIN Tagesabzug. Die
        // Vorlaufzeit nach einem hypothetischen Abzug waere ~23h55m, weit ueber
        // MAX_NIGHT_SHIFT_LEAD_TIME (12h) - eindeutig kein Nachtschicht-Fall.
        val engine = engineWith(shift("Früh", listOf("F"), LocalTime.of(6, 5)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("F", LocalDateTime.of(2025, 1, 20, 6, 0)))
        )

        assertEquals(1, matches.size)
        assertEquals(LocalDateTime.of(2025, 1, 20, 6, 5), matches[0].calculatedAlarmTime)
    }

    @Test
    fun `Weckzeit gleich Schichtbeginn ergibt selben Tag`() = runTest {
        val engine = engineWith(shift("Spät", listOf("S"), LocalTime.of(14, 0)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("S", LocalDateTime.of(2025, 3, 10, 14, 0)))
        )

        assertEquals(1, matches.size)
        // alarmDateTime == shiftStart -> nicht "isAfter" -> selber Tag
        assertEquals(LocalDateTime.of(2025, 3, 10, 14, 0), matches[0].calculatedAlarmTime)
    }

    @Test
    fun `Nur ganze Woerter matchen - kein Teilstring-Treffer`() = runTest {
        // Keyword "F" darf NICHT in "Fortbildung" matchen (Wortgrenzen-Regex)
        val engine = engineWith(shift("Früh", listOf("F"), LocalTime.of(5, 30)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("Fortbildung", LocalDateTime.of(2025, 1, 20, 8, 0)))
        )

        assertTrue("Teilstring 'F' in 'Fortbildung' darf nicht matchen", matches.isEmpty())
    }

    @Test
    fun `Nicht passende Termine erzeugen keinen Wecker`() = runTest {
        val engine = engineWith(shift("Früh", listOf("F"), LocalTime.of(5, 30)))

        val matches = engine.getAllMatchingShifts(
            listOf(event("Geburtstag", LocalDateTime.of(2025, 1, 20, 18, 0)))
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `Leere Konfiguration liefert keine Treffer`() = runTest {
        val engine = ShiftRecognitionEngine(
            FakeShiftConfigRepository(ShiftConfig(definitions = emptyList()))
        )

        val matches = engine.getAllMatchingShifts(
            listOf(event("F", LocalDateTime.of(2025, 1, 20, 6, 0)))
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `Treffer sind nach Weckzeit sortiert`() = runTest {
        val engine = engineWith(
            shift("Früh", listOf("F"), LocalTime.of(5, 30)),
            shift("Spät", listOf("S"), LocalTime.of(12, 30))
        )

        // Spätschicht-Event zuerst in der Liste, muss aber nach Weckzeit hinter Früh landen
        val matches = engine.getAllMatchingShifts(
            listOf(
                event("S", LocalDateTime.of(2025, 1, 20, 14, 0)),
                event("F", LocalDateTime.of(2025, 1, 20, 6, 0))
            )
        )

        assertEquals(2, matches.size)
        assertEquals(LocalDateTime.of(2025, 1, 20, 5, 30), matches[0].calculatedAlarmTime)
        assertTrue(
            "Erste Weckzeit muss vor der zweiten liegen",
            matches[0].calculatedAlarmTime.isBefore(matches[1].calculatedAlarmTime)
        )
    }
}
