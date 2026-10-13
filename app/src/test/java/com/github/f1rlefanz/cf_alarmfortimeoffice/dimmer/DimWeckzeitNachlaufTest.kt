package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * „Bis zur Weckzeit + X Minuten, spätestens um die Uhrzeit" ([DimWindow.weckzeitNachlaufMinuten]).
 *
 * WARUM (Nutzerwunsch 13.10.2026): Im Winter ist es beim Frühdienst-Wecken stockfinster, und ein
 * schlagartig helles Display blendet. Zwei Entscheidungen des Nutzers sind hier festgeschrieben:
 * die Uhrzeit bleibt eine HARTE Obergrenze („spätestens" bleibt wahr), und „Nicht stören" macht
 * den Nachlauf NICHT mit (Aufruf mit `mitWeckzeitNachlauf = false`).
 */
class DimWeckzeitNachlaufTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun t(datum: String, uhrzeit: String): Long =
        LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()

    private fun nachtfenster(nachlauf: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = 7 * 60,
        weckzeitNachlaufMinuten = nachlauf
    )

    private fun nacht(nachlauf: Int, weckzeiten: List<Long>): LongRange? =
        DimWindowResolver.resolveFreeWindow(nachtfenster(nachlauf), LocalDate.parse("2026-10-12"), zone, weckzeiten)

    @Test
    fun `Fruehdienst 05 30 plus 45 Minuten endet um 06 15`() {
        assertEquals(t("2026-10-13", "06:15"), nacht(45, listOf(t("2026-10-13", "05:30")))!!.last)
    }

    @Test
    fun `Nachlauf 0 verhaelt sich wie bisher - Ende genau an der Weckzeit`() {
        assertEquals(t("2026-10-13", "05:30"), nacht(0, listOf(t("2026-10-13", "05:30")))!!.last)
    }

    @Test
    fun `die Uhrzeit bleibt harte Obergrenze - 06 50 plus 30 endet um 07 00`() {
        assertEquals(t("2026-10-13", "07:00"), nacht(30, listOf(t("2026-10-13", "06:50")))!!.last)
    }

    @Test
    fun `ohne Wecker in der Nacht endet das Fenster unveraendert an der Uhrzeit`() {
        // Der Nachlauf haengt an einer GEFUNDENEN Weckzeit - eine Spaetdienst-Weckzeit nach der
        // Schranke gehoert nicht zu dieser Nacht und verlaengert nichts.
        assertEquals(t("2026-10-13", "07:00"), nacht(45, listOf(t("2026-10-13", "12:30")))!!.last)
    }

    @Test
    fun `der Nachlauf wird geklemmt - negativ wie 0, uebergross wie das Maximum`() {
        val wecker = listOf(t("2026-10-13", "04:00"))
        assertEquals(t("2026-10-13", "04:00"), nacht(-30, wecker)!!.last)
        assertEquals(
            t("2026-10-13", "04:00") + DimWindowResolver.NACHLAUF_MAX_MINUTEN * 60_000L,
            nacht(10_000, wecker)!!.last
        )
    }

    @Test
    fun `ein alter Regelbestand ohne das Feld liest sich als Nachlauf 0`() {
        assertEquals(0, DimWindow().weckzeitNachlaufMinuten)
    }

    // ------------------------------------------------- „Nicht stören" folgt OHNE Nachlauf

    private val freiRegel = DimRule(
        name = "Nacht",
        shiftPattern = DimRule.SHIFT_FREE,
        windows = listOf(nachtfenster(45))
    )

    private fun spannen(mitNachlauf: Boolean) = DimWindowResolver.buildRuleSpans(
        alarms = emptyList(),
        horizonDays = 2,
        today = LocalDate.parse("2026-10-13"),
        zone = zone,
        ruleForShift = { null },
        ruleForFreeDay = { freiRegel },
        weckzeiten = listOf(t("2026-10-13", "05:30")),
        mitWeckzeitNachlauf = mitNachlauf
    )

    @Test
    fun `der Dimmer rechnet mit Nachlauf`() {
        val nacht = spannen(mitNachlauf = true).first { it.range.first == t("2026-10-12", "22:00") }
        assertEquals(t("2026-10-13", "06:15"), nacht.range.last)
    }

    @Test
    fun `ohne Nachlauf - so liest DND-Modus 1 - endet dieselbe Nacht an der Weckzeit`() {
        val nacht = spannen(mitNachlauf = false).first { it.range.first == t("2026-10-12", "22:00") }
        assertEquals(t("2026-10-13", "05:30"), nacht.range.last)
    }
}
