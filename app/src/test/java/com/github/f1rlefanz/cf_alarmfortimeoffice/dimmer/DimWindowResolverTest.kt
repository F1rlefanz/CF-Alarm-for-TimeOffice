package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver.DimSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit-Tests der reinen Fenster-Mathematik ([DimWindowResolver]). Deterministisch gegen UTC.
 * Hält die kniffligen Fälle fest: CLOCK „vor der Weckzeit", SHIFT_END-Auflösung + 0-Fall,
 * Roll-forward über Mitternacht und die „dunkelste-Spanne-gewinnt"-Auswahl.
 */
class DimWindowResolverTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // --- resolveShiftWindow ---

    @Test
    fun `CLOCK-Start zielt auf die Uhrzeit VOR der Weckzeit (Vorabend)`() {
        val w = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 20 * 60,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
        )
        val alarm = ep(2026, 1, 15, 5, 30)

        val r = DimWindowResolver.resolveShiftWindow(w, alarm, 0, zone)!!

        assertEquals(ep(2026, 1, 14, 20, 0), r.first) // Vorabend
        assertEquals(ep(2026, 1, 15, 5, 30), r.last)  // Weckzeit
    }

    @Test
    fun `ALARM-Anker loesen relativ zur Weckzeit auf`() {
        val w = DimWindow(
            startAnchor = DimAnchor.ALARM, startOffsetMinutes = -120,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
        )
        val alarm = ep(2026, 1, 15, 5, 30)

        val r = DimWindowResolver.resolveShiftWindow(w, alarm, 0, zone)!!

        assertEquals(ep(2026, 1, 15, 3, 30), r.first)
        assertEquals(ep(2026, 1, 15, 5, 30), r.last)
    }

    @Test
    fun `SHIFT_END-Start + CLOCK-Ende ergibt den ND-Tagschlaf am Folgetag`() {
        // ND-Schicht Vorabend 20:15 -> Alarm; Schicht endet am Folgetag 06:00. Tagschlaf ab Ende+60 bis 14:00.
        val w = DimWindow(
            startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 60,
            endAnchor = DimAnchor.CLOCK, endClockMinutes = 14 * 60
        )
        val alarm = ep(2026, 1, 15, 20, 15)
        val shiftEnd = ep(2026, 1, 16, 6, 0)

        val r = DimWindowResolver.resolveShiftWindow(w, alarm, shiftEnd, zone)!!

        assertEquals(ep(2026, 1, 16, 7, 0), r.first)   // Schichtende + 60
        assertEquals(ep(2026, 1, 16, 14, 0), r.last)   // 14:00, über den Tag der Weckzeit hinaus gerollt
    }

    @Test
    fun `SHIFT_END ohne bekanntes Schichtende liefert null`() {
        val w = DimWindow(startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 60, endAnchor = DimAnchor.CLOCK)
        val alarm = ep(2026, 1, 15, 20, 15)

        assertNull(DimWindowResolver.resolveShiftWindow(w, alarm, 0, zone))
    }

    @Test
    fun `Ende kleiner-gleich Start ergibt kein Fenster`() {
        val w = DimWindow(
            startAnchor = DimAnchor.ALARM, startOffsetMinutes = 0,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = -60
        )
        assertNull(DimWindowResolver.resolveShiftWindow(w, ep(2026, 1, 15, 5, 30), 0, zone))
    }

    // --- resolveFreeWindow ---

    @Test
    fun `Freies Fenster ueber Mitternacht rollt das Ende auf den Folgetag`() {
        val w = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.CLOCK, endClockMinutes = 6 * 60
        )
        val r = DimWindowResolver.resolveFreeWindow(w, LocalDate.of(2026, 1, 15), zone)!!

        assertEquals(ep(2026, 1, 15, 22, 0), r.first)
        assertEquals(ep(2026, 1, 16, 6, 0), r.last)
    }

    @Test
    fun `Freies Fenster mit Nicht-CLOCK-Anker ist nicht aufloesbar`() {
        val w = DimWindow(startAnchor = DimAnchor.CLOCK, endAnchor = DimAnchor.ALARM)
        assertNull(DimWindowResolver.resolveFreeWindow(w, LocalDate.of(2026, 1, 15), zone))
    }

    // --- activeSpan (dunkelste gewinnt) ---

    @Test
    fun `Bei Ueberlappung gewinnt die dunkelste Spanne`() {
        val mild = DimSpan(0L..100L, strength = 40, warmth = 30)
        val dark = DimSpan(50L..150L, strength = 70, warmth = 10)

        val active = DimWindowResolver.activeSpan(listOf(mild, dark), now = 60L)

        assertEquals(70, active!!.strength)
    }

    @Test
    fun `Bei gleicher Verdunkelung entscheidet die hoehere Waerme`() {
        val a = DimSpan(0L..100L, strength = 50, warmth = 20)
        val b = DimSpan(0L..100L, strength = 50, warmth = 40)

        val active = DimWindowResolver.activeSpan(listOf(a, b), now = 50L)

        assertEquals(40, active!!.warmth)
    }

    @Test
    fun `Keine Spanne enthaelt now - kein aktives Fenster`() {
        val s = DimSpan(0L..100L, strength = 50, warmth = 20)
        assertNull(DimWindowResolver.activeSpan(listOf(s), now = 200L))
    }

    // --- buildRuleSpans: "immer 22-7 dimmen, außer an Nachtdienst-Nächten" ---

    @Test
    fun `Universal 22-7 dimmt LUECKENLOS jede Nacht, nur die Nachtschicht-Nacht ausgenommen`() {
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                    endAnchor = DimAnchor.CLOCK, endClockMinutes = 7 * 60
                )
            )
        )
        // Nachtschicht-Regel mit LEERER Fensterliste = Unterdrückung.
        val nd = DimRule(id = "n", name = "ND frei", shiftPattern = "Nachtschicht", enabled = true, windows = emptyList())
        val rules = listOf(universal, nd)
        val forShift = { name: String ->
            val en = rules.filter { it.enabled }
            en.firstOrNull { it.shiftPattern.equals(name, ignoreCase = true) }
                ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
        }
        val forFree = {
            val en = rules.filter { it.enabled }
            en.firstOrNull { it.shiftPattern == DimRule.SHIFT_FREE }
                ?: en.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
        }

        // Mo 12.01. Start; Di=Frühschicht, Mi=Nachtschicht (21:00–06:00), Do/Fr frei.
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 30), "Frühschicht", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 14, 20, 15), "Nachtschicht", ep(2026, 1, 15, 6, 0))
        )

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = alarms, horizonDays = 5, today = today, zone = zone,
            ruleForShift = forShift, ruleForFreeDay = forFree
        )

        // 4 Nächte gedimmt (Mo, Di, Do, Fr) – die Nachtschicht-Arbeitsnacht (Mi) NICHT.
        assertEquals(4, spans.size)
        // Di-Nacht (nach der Frühschicht) IST gedimmt – genau hier hatte das alte Modell eine Lücke.
        assertTrue(spans.any { ep(2026, 1, 13, 23, 0) in it.range })
        // Mi→Do (Arbeitsnacht der Nachtschicht) NICHT gedimmt.
        assertTrue(spans.none { ep(2026, 1, 14, 23, 0) in it.range })
        // Do-Nacht (frei) wieder gedimmt.
        assertTrue(spans.any { ep(2026, 1, 15, 23, 0) in it.range })
    }
}
