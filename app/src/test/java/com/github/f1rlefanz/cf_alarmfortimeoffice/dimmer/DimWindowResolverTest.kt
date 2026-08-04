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

    // --- buildDefaultNightSpans: eingebauter Nacht-Standard (v1.17.0) ---

    @Test
    fun `Nacht-Standard endet an Tagen mit Alarm dynamisch beim tatsaechlichen Wecker`() {
        // Spaetdienst-Nacht (12.01.), Folgetag 13.01. ist Fruehdienst mit 5-Uhr-Wecker.
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(DimWindowResolver.AlarmSlot(ep(2026, 1, 13, 5, 0), "Fruehdienst", 0))

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = alarms, horizonDays = 3, today = today, zone = zone,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        val night = spans.first { ep(2026, 1, 12, 23, 0) in it.range }
        assertEquals(ep(2026, 1, 12, 22, 0), night.range.first)
        assertEquals(ep(2026, 1, 13, 5, 0), night.range.last) // endet beim ECHTEN Wecker, nicht fix 07:00
    }

    @Test
    fun `Nacht-Standard nutzt an alarmlosen Tagen die feste Ende-Uhrzeit`() {
        val today = LocalDate.of(2026, 1, 12)

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = emptyList(), horizonDays = 1, today = today, zone = zone,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        assertEquals(1, spans.size)
        assertEquals(ep(2026, 1, 12, 22, 0), spans[0].range.first)
        assertEquals(ep(2026, 1, 13, 7, 0), spans[0].range.last)
    }

    @Test
    fun `Eine vorhandene Regel ersetzt den Nacht-Standard fuer ihren Tag komplett`() {
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(DimWindowResolver.AlarmSlot(ep(2026, 1, 12, 20, 0), "Nachtdienst", 0))

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = alarms, horizonDays = 1, today = today, zone = zone,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { name -> name == "Nachtdienst" }
        )

        assertTrue(spans.isEmpty())
    }

    @Test
    fun `Regression - Nachmittagswecker gefolgt von wecker-losem Tag verliert nicht die eigene Nacht`() {
        // Realer Vorfall 03.-05.08.2026: S2-Wecker (14:30, kein Morgen-Wecker) am 12.01., 13.01.
        // ganz ohne Termin/Wecker, 14.01. Fruehschicht (05:30). Das alte Modell erzeugte fuer den
        // 12.01. nur ein RUECKWAERTS-Fenster bis 14:30 (endet am eigenen Wecker) und ueberspringt
        // den 13.01. komplett, weil der 14.01. einen Wecker hat - dessen Fenster reicht aber nur
        // bis 22:00 Uhr des 13.01. zurueck, NICHT bis 22:00 Uhr des 12.01. Die Nacht vom 12.01. auf
        // den 13.01. blieb dadurch komplett ungedimmt.
        val today = LocalDate.of(2026, 1, 12)
        val alarms = listOf(
            DimWindowResolver.AlarmSlot(ep(2026, 1, 12, 14, 30), "S2", 0),
            DimWindowResolver.AlarmSlot(ep(2026, 1, 14, 5, 30), "Fruehdienst", 0),
        )

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = alarms, horizonDays = 3, today = today, zone = zone,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        // Die Nacht vom 12.01. auf den 13.01. muss abgedeckt sein - 23:00 Uhr des 12.01. faellt in
        // irgendeine Spanne, UND deren Ende liegt VOR 22:00 Uhr des 13.01. (sonst waere es die
        // rueckwaerts-Spanne des 14.01., die faelschlich bis in den 12.01. zurueckreichen wuerde).
        val night = spans.firstOrNull { ep(2026, 1, 12, 23, 0) in it.range }
        assertTrue("Nacht 12.01.->13.01. hat keine Spanne - die alte Luecke ist zurueck", night != null)
        assertTrue(night!!.range.last < ep(2026, 1, 13, 22, 0))

        // Die Nacht vom 13.01. auf den 14.01. bleibt weiterhin bis zum echten Wecker dynamisch.
        val secondNight = spans.first { ep(2026, 1, 13, 23, 0) in it.range }
        assertEquals(ep(2026, 1, 13, 22, 0), secondNight.range.first)
        assertEquals(ep(2026, 1, 14, 5, 30), secondNight.range.last)
    }

    // --- mergeToTimeline: Vorschau-Zeitleiste ---

    @Test
    fun `mergeToTimeline fasst ueberlappende Spannen zur dunkelsten zusammen`() {
        val mild = DimSpan(0L..200L, strength = 40, warmth = 30)
        val dark = DimSpan(100L..200L, strength = 70, warmth = 10)

        val timeline = DimWindowResolver.mergeToTimeline(listOf(mild, dark))

        assertEquals(2, timeline.size)
        assertTrue(timeline.any { it.range == 0L..100L && it.strength == 40 })
        assertTrue(timeline.any { it.range == 100L..200L && it.strength == 70 })
    }

    @Test
    fun `mergeToTimeline haelt getrennte Spannen als getrennte Abschnitte`() {
        val a = DimSpan(0L..100L, strength = 50, warmth = 20)
        val b = DimSpan(200L..300L, strength = 50, warmth = 20)

        val timeline = DimWindowResolver.mergeToTimeline(listOf(a, b))

        assertEquals(2, timeline.size)
    }

    @Test
    fun `mergeToTimeline ist leer ohne Spannen`() {
        assertTrue(DimWindowResolver.mergeToTimeline(emptyList()).isEmpty())
    }

    // --- isOverrideStale / applyStrengthDelta: Dimmer-Korrektur-Override (Feature C) ---

    @Test
    fun `Override ist stale wenn kein Fenster aktiv ist`() {
        assertTrue(DimWindowResolver.isOverrideStale(activeWindowEnd = null, activeStrength = null, overrideWindowEnd = 500L, overrideStrength = 40))
    }

    @Test
    fun `Override gilt weiter solange windowEnd und strength zur aktiven Spanne passen`() {
        assertTrue(DimWindowResolver.isOverrideStale(activeWindowEnd = 500L, activeStrength = 40, overrideWindowEnd = 500L, overrideStrength = 40).not())
    }

    @Test
    fun `Override ist stale wenn sich das aktive Fenster geaendert hat`() {
        // naechste Fenstergrenze ist bereits erreicht (naechster Tick hat neu berechnet)
        assertTrue(DimWindowResolver.isOverrideStale(activeWindowEnd = 600L, activeStrength = 40, overrideWindowEnd = 500L, overrideStrength = 40))
    }

    @Test
    fun `Override ist stale wenn eine andere, gleich endende Quelle uebernimmt`() {
        // Regression: Wellness-Fenster und Nacht-Standard-/Regel-Fenster teilen sich oft denselben
        // windowEnd (beide ALARM-Offset 0 = Weckzeit) - "darkest wins" wechselt hier auf eine
        // Quelle mit anderer Staerke, obwohl range.last identisch bleibt. windowEnd allein wuerde
        // das faelschlich als "gleiches Fenster" durchgehen lassen.
        assertTrue(DimWindowResolver.isOverrideStale(activeWindowEnd = 500L, activeStrength = 70, overrideWindowEnd = 500L, overrideStrength = 40))
    }

    @Test
    fun `applyStrengthDelta addiert das Delta innerhalb der Grenzen`() {
        assertEquals(60, DimWindowResolver.applyStrengthDelta(base = 50, delta = 10, max = 85))
        assertEquals(40, DimWindowResolver.applyStrengthDelta(base = 50, delta = -10, max = 85))
    }

    @Test
    fun `applyStrengthDelta klemmt auf 0 nach unten`() {
        assertEquals(0, DimWindowResolver.applyStrengthDelta(base = 5, delta = -20, max = 85))
    }

    @Test
    fun `applyStrengthDelta klemmt auf max nach oben`() {
        assertEquals(85, DimWindowResolver.applyStrengthDelta(base = 80, delta = 20, max = 85))
    }
}
