package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Die Weckzeit-Regel der Erkennung als reine Funktion [weckzeitpunkt] (#192).
 *
 * EINE Falltabelle mit fest eingetragenen Erwartungen, zweimal geprueft: direkt an der Funktion
 * und durch die [ShiftRecognitionEngine]. Die Erkennung wird gegen die LITERALE geprueft, nie gegen
 * `weckzeitpunkt(...)`: riefen beide Seiten dieselbe veraenderte Funktion, bliebe der Test bei jeder
 * Veraenderung gruen und bewiese nicht, dass die Erkennung genau diese Regel rechnet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeckzeitpunktTest {

    private data class Fall(
        val name: String,
        val beginn: LocalDateTime,
        val ganztaegig: Boolean,
        val weckzeit: LocalTime,
        val erwartet: LocalDateTime
    )

    private fun aug(tag: Int, stunde: Int, minute: Int = 0) = LocalDateTime.of(2026, 8, tag, stunde, minute)

    private val faelle = listOf(
        Fall("Tagdienst: Weckzeit vor Beginn, derselbe Tag", aug(10, 6), false, LocalTime.of(4, 30), aug(10, 4, 30)),
        Fall("Nachtdienst ueber Mitternacht: Vortag", aug(10, 0, 30), false, LocalTime.of(23, 30), aug(9, 23, 30)),
        Fall("genau 12 h Vorlauf: noch Vortag", aug(10, 6), false, LocalTime.of(18, 0), aug(9, 18)),
        Fall("12 h 1 min Vorlauf: derselbe Tag", aug(10, 6), false, LocalTime.of(17, 59), aug(10, 17, 59)),
        Fall("Weckzeit gleich Beginn: derselbe Tag", aug(10, 6), false, LocalTime.of(6, 0), aug(10, 6)),
        Fall("Weckzeit 5 min nach Beginn: kein Tag zu frueh", aug(10, 6), false, LocalTime.of(6, 5), aug(10, 6, 5)),
        Fall("Beginn genau Mitternacht: Vortag", aug(10, 0), false, LocalTime.of(23, 0), aug(9, 23)),
        Fall("Beginn und Weckzeit Mitternacht: derselbe Tag", aug(10, 0), false, LocalTime.MIDNIGHT, aug(10, 0)),
        // Ohne die Ausnahme fuer ganztaegige Termine laegen beide auf dem Vortag (Vorlauf 11 h bzw. 30 min).
        Fall("ganztaegig, Weckzeit 13:00: am Tag selbst", aug(10, 0), true, LocalTime.of(13, 0), aug(10, 13)),
        Fall("ganztaegig, Weckzeit 23:30: am Tag selbst", aug(10, 0), true, LocalTime.of(23, 30), aug(10, 23, 30))
    )

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `Randfaelle direkt an der reinen Funktion`() {
        faelle.forEach { fall ->
            assertEquals(fall.name, fall.erwartet, weckzeitpunkt(fall.beginn, fall.ganztaegig, fall.weckzeit))
        }
    }

    @Test
    fun `die Zonen-Variante liest den Beginn in der uebergebenen Zone und rechnet dieselbe Regel`() {
        faelle.forEach { fall ->
            val beginn = fall.beginn.atZone(berlin).toInstant()
            assertEquals(fall.name, fall.erwartet, weckzeitpunkt(beginn, fall.ganztaegig, fall.weckzeit, berlin))
        }
        // 16.10.2026 23:00 UTC ist in Berlin schon der 17.10., 01:00: die Weckzeit 00:30 liegt dort
        // am 17.10. vor Beginn. In UTC gelesen beginnt der Dienst am 16.10. um 23:00.
        val beginn = Instant.parse("2026-10-16T23:00:00Z")
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 30), weckzeitpunkt(beginn, false, LocalTime.of(0, 30), berlin))
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 30), weckzeitpunkt(beginn, false, LocalTime.of(0, 30), ZoneOffset.UTC))
    }

    /**
     * Zeitumstellung: die 12-h-Grenze gilt fuer die Wanduhr, nicht fuer die verstrichene Zeit - so
     * rechnete `calculateAlarmTime()` vor #192 (`Duration.between` auf `LocalDateTime`). Eine
     * Umstellung auf echte Dauer verschoebe an beiden Umstellungstagen den Wecker um einen Tag.
     */
    @Test
    fun `Zeitumstellung im Herbst - 12 h auf der Uhr sind noch Vortag, obwohl 13 h vergehen`() {
        val beginn = LocalDateTime.of(2026, 10, 25, 6, 0).atZone(berlin).toInstant()
        val vortag = LocalDateTime.of(2026, 10, 24, 18, 0)
        assertEquals("Testvoraussetzung: Umstellungstag", Duration.ofHours(13), Duration.between(vortag.atZone(berlin).toInstant(), beginn))

        assertEquals(vortag, weckzeitpunkt(beginn, false, LocalTime.of(18, 0), berlin))
    }

    @Test
    fun `Zeitumstellung im Fruehjahr - 12 h 30 auf der Uhr sind kein Vortag, obwohl nur 11 h 30 vergehen`() {
        val beginn = LocalDateTime.of(2027, 3, 28, 6, 0).atZone(berlin).toInstant()
        val vortag = LocalDateTime.of(2027, 3, 27, 17, 30)
        assertEquals("Testvoraussetzung: Umstellungstag", Duration.ofMinutes(11 * 60 + 30), Duration.between(vortag.atZone(berlin).toInstant(), beginn))

        assertEquals(LocalDateTime.of(2027, 3, 28, 17, 30), weckzeitpunkt(beginn, false, LocalTime.of(17, 30), berlin))
    }

    private class FesteKonfiguration(private val config: ShiftConfig) : IShiftConfigRepository {
        override val shiftConfig: Flow<ShiftConfig> = flowOf(config)
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = Result.success(config)
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = Result.success(Unit)
    }

    /** Mutationsprobe: wer [weckzeitpunkt] veraendert, macht DIESEN Test rot - die Erkennung hat keine eigene Kopie. */
    @Test
    fun `die Erkennung liefert fuer jeden Randfall denselben Weckzeitpunkt`() = runTest {
        faelle.forEach { fall ->
            val termin = CalendarEvent(
                id = "e-${fall.name}",
                title = "WZ",
                startTime = fall.beginn,
                endTime = fall.beginn.plusHours(8),
                calendarId = "cal1",
                isAllDay = fall.ganztaegig
            )
            val config = ShiftConfig(
                autoAlarmEnabled = true,
                definitions = listOf(
                    ShiftDefinition(id = "wz", name = "Weckzeit-Probe", keywords = listOf("WZ"), alarmTime = fall.weckzeit)
                )
            )
            val engine = ShiftRecognitionEngine(FesteKonfiguration(config), UnconfinedTestDispatcher(testScheduler))

            val treffer = engine.getAllMatchingShifts(listOf(termin))

            assertEquals(fall.name, 1, treffer.size)
            assertEquals(fall.name, fall.erwartet, treffer.single().calculatedAlarmTime)
        }
    }
}
