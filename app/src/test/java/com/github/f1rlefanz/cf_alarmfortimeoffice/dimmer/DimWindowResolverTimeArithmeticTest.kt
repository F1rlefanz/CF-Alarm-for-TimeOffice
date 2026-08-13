package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver.DimSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Zeitrechnungs-Regressionen von [DimWindowResolver] - bewusst getrennt von
 * [DimWindowResolverTest] (das haelt die fachlichen Kern-Szenarien fest, deterministisch gegen UTC).
 * Hier geht es um die drei Faelle, in denen die Mathematik selbst falsch war:
 *
 * 1. **DST** (`Europe/Berlin`, 29.03./25.10.2026): CLOCK-Anker wurden als
 *    "Mitternacht-Instant + Minuten-Millis" gerechnet. An den Umstellungstagen ist ein Kalendertag
 *    23 h bzw. 25 h lang - aus 22:00 wurde dadurch 23:00 bzw. 21:00. Genau dieselbe Falle wie beim
 *    DND-Rufbereitschaft-Cutoff, dort schon behoben.
 * 2. **Datumswechsel**: ein am Vorabend gestartetes Fenster verschwand nach Mitternacht aus jeder
 *    Schleifen-Iteration - jede Neuberechnung (App-Update, 6h-Wartung, Korrektur-Notification-Tap)
 *    hielt die laufende Nacht dann fuer "kein aktives Fenster" und schaltete Dimmen + DND ab.
 * 3. **Fenster-Ende inklusiv**: ein Tick exakt auf `range.last` galt noch als "im Fenster",
 *    waehrend der naechste Wechsel strikt auf `> now` geplant wird - der Zustand "aus" wurde fuer
 *    diesen Rand nie berechnet.
 */
class DimWindowResolverTimeArithmeticTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun ep(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun nightWindow(startClock: Int, endClock: Int) = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = startClock,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = endClock
    )

    // --- 1. DST: echte Wanduhrzeit statt Mitternacht + Millis-Offset ---

    @Test
    fun `DST-Vorspringen - 22 bis 7 Uhr bleibt 22 bis 7 Uhr Wanduhrzeit`() {
        // Nacht 29.03.2026 (02:00 -> 03:00, der Kalendertag ist nur 23 h lang).
        val r = DimWindowResolver.resolveFreeWindow(
            nightWindow(22 * 60, 7 * 60), LocalDate.of(2026, 3, 29), berlin
        )!!

        assertEquals(ep(berlin, 2026, 3, 29, 22, 0), r.first)
        assertEquals(ep(berlin, 2026, 3, 30, 7, 0), r.last)
    }

    @Test
    fun `DST-Zurueckspringen - 22 bis 7 Uhr bleibt 22 bis 7 Uhr Wanduhrzeit`() {
        // Nacht 25.10.2026 (03:00 -> 02:00, der Kalendertag ist 25 h lang).
        val r = DimWindowResolver.resolveFreeWindow(
            nightWindow(22 * 60, 7 * 60), LocalDate.of(2026, 10, 25), berlin
        )!!

        assertEquals(ep(berlin, 2026, 10, 25, 22, 0), r.first)
        assertEquals(ep(berlin, 2026, 10, 26, 7, 0), r.last)
    }

    @Test
    fun `CLOCK-Start vor der Weckzeit trifft am DST-Tag die richtige Vorabend-Uhrzeit`() {
        // Wecker am Umstellungstag 29.03.2026 05:30 (nach dem Vorspringen), CLOCK-Start 20:00.
        // Alt: 20:00 des 29.03. wurde als 21:00 aufgeloest, minus 24h-Millis -> 28.03. 21:00.
        val w = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 20 * 60,
            endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
        )
        val alarm = ep(berlin, 2026, 3, 29, 5, 30)

        val r = DimWindowResolver.resolveShiftWindow(w, alarm, 0, berlin)!!

        assertEquals(ep(berlin, 2026, 3, 28, 20, 0), r.first)
        assertEquals(alarm, r.last)
    }

    @Test
    fun `CLOCK-Ende rollt am DST-Tag auf die richtige Uhrzeit des Folgetags`() {
        // ND-Tagschlaf: Schichtende 25.10.2026 06:00, Tagschlaf-Ende CLOCK 14:00. Der Roll-forward
        // darf keine 24h-Millis addieren (der 25.10. hat 25 h).
        val w = DimWindow(
            startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 60,
            endAnchor = DimAnchor.CLOCK, endClockMinutes = 14 * 60
        )
        val alarm = ep(berlin, 2026, 10, 24, 20, 15)
        val shiftEnd = ep(berlin, 2026, 10, 25, 6, 0)

        val r = DimWindowResolver.resolveShiftWindow(w, alarm, shiftEnd, berlin)!!

        assertEquals(shiftEnd + 60 * 60_000L, r.first)
        assertEquals(ep(berlin, 2026, 10, 25, 14, 0), r.last)
    }

    @Test
    fun `Nacht-Standard dimmt am DST-Vorspringen-Tag ab der eingestellten Uhrzeit`() {
        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = emptyList(), horizonDays = 1, today = LocalDate.of(2026, 3, 29), zone = berlin,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        val night = spans.first { ep(berlin, 2026, 3, 29, 23, 0) in it.range }
        assertEquals(ep(berlin, 2026, 3, 29, 22, 0), night.range.first)
        assertEquals(ep(berlin, 2026, 3, 30, 7, 0), night.range.last)
    }

    // --- 2. Datumswechsel: die laufende Nacht darf nach 00:00 nicht verschwinden ---

    @Test
    fun `Nacht-Standard - die laufende Nacht bleibt nach Mitternacht aktiv`() {
        // Freier Tag -> freier Tag, Nacht-Standard 22:00-07:00. Es ist 03:00 des Folgetags,
        // die Neuberechnung laeuft mit today = FOLGETAG (z. B. App-Update um 03:00, 6h-Wartung).
        val today = LocalDate.of(2026, 1, 13)
        val now = ep(utc, 2026, 1, 13, 3, 0)

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = emptyList(), horizonDays = 14, today = today, zone = utc,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        val active = DimWindowResolver.activeSpan(spans, now)
        assertNotNull(
            "Die um 22:00 des Vortags gestartete Nacht faellt nach dem Datumswechsel weg - " +
                "Dimmen UND DND (Modus 1) wuerden mitten in der Nacht abgeschaltet",
            active
        )
        assertEquals(ep(utc, 2026, 1, 12, 22, 0), active!!.range.first)
        assertEquals(ep(utc, 2026, 1, 13, 7, 0), active.range.last)
    }

    @Test
    fun `Regel-Fenster - die laufende CLOCK-Nacht bleibt nach Mitternacht aktiv`() {
        val universal = DimRule(
            id = "u", name = "Nacht", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(nightWindow(22 * 60, 7 * 60)), strength = 55, warmth = 30
        )
        val today = LocalDate.of(2026, 1, 13)
        val now = ep(utc, 2026, 1, 13, 3, 0)

        val spans = DimWindowResolver.buildRuleSpans(
            alarms = emptyList(), horizonDays = 14, today = today, zone = utc,
            ruleForShift = { universal }, ruleForFreeDay = { universal }
        )

        val active = DimWindowResolver.activeSpan(spans, now)
        assertNotNull("Die CLOCK<->CLOCK-Nacht des Vortags fehlt nach dem Datumswechsel", active)
        assertEquals(ep(utc, 2026, 1, 12, 22, 0), active!!.range.first)
        assertEquals(ep(utc, 2026, 1, 13, 7, 0), active.range.last)
    }

    @Test
    fun `Der Rueckblick erzeugt keine zusaetzliche Nacht wenn der Folgetag einen Wecker hat`() {
        // Gegenprobe zum Rueckblick: mit Wecker am 13.01. deckt dessen Rueckwaerts-Fenster die
        // Nacht 12.->13.01. ab - es darf dafuer nicht ZWEI unterschiedliche Spannen geben.
        val today = LocalDate.of(2026, 1, 13)
        val alarms = listOf(DimWindowResolver.AlarmSlot(ep(utc, 2026, 1, 13, 5, 0), "Fruehdienst", 0))

        val spans = DimWindowResolver.buildDefaultNightSpans(
            alarms = alarms, horizonDays = 2, today = today, zone = utc,
            startClockMinutes = 22 * 60, freeDayEndClockMinutes = 7 * 60,
            strength = 60, warmth = 40,
            isExcluded = { false }
        )

        val covering = spans.filter { ep(utc, 2026, 1, 13, 3, 0) in it.range }
        assertEquals(1, covering.size)
        assertEquals(ep(utc, 2026, 1, 13, 5, 0), covering[0].range.last)
    }

    // --- 3. Fenster-Ende ist halb offen ---

    @Test
    fun `Ein Tick exakt auf dem Fenster-Ende gilt NICHT mehr als aktiv`() {
        val s = DimSpan(100L..200L, strength = 50, warmth = 20)
        assertNull(DimWindowResolver.activeSpan(listOf(s), now = 200L))
    }

    @Test
    fun `Ein Tick exakt auf dem Fenster-Start gilt als aktiv`() {
        val s = DimSpan(100L..200L, strength = 50, warmth = 20)
        assertEquals(50, DimWindowResolver.activeSpan(listOf(s), now = 100L)!!.strength)
    }

    @Test
    fun `An der Grenze zweier anschliessender Fenster gewinnt das FOLGENDE`() {
        // Das ENDENDE Fenster ist absichtlich das DUNKLERE: waere die Zugehoerigkeit noch inklusiv,
        // gehoerte es bei now = 100 weiter dazu und "dunkelste gewinnt" ergaebe 70. Nur weil das
        // Ende halb offen ist, bleibt allein das folgende Fenster uebrig. Mit umgekehrten Staerken
        // waere der Test unter BEIDEN Semantiken gruen - und damit wertlos.
        val first = DimSpan(0L..100L, strength = 70, warmth = 50)
        val second = DimSpan(100L..200L, strength = 30, warmth = 10)

        val active = DimWindowResolver.activeSpan(listOf(first, second), now = 100L)

        assertEquals(30, active!!.strength)
        assertEquals(100L..200L, active.range)
    }
}
