package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver.AlarmSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Die Blockposition eines Schicht-Tages (erster / mittlerer / letzter / einzelner Tag einer Folge
 * gleicher Schichten) und ihre Wirkung auf die Fenster einer Regel.
 *
 * HERGANG (18.09.2026): Drei Nachtdienste Fr-So. Der Schlaf nach der LETZTEN Nacht ist kuerzer
 * (Umstellung zurueck auf den Tag: Montag bis 12:00 statt bis 14:00). Das Modell kannte nur "jeder
 * Tag dieser Schicht bekommt dieselben Fenster" - die Vorschau zeigte deshalb Mo. 06:45 -> 14:00.
 * Seither traegt ein Fenster die Positionen, an denen es gilt; der Resolver leitet die Position
 * aus den Nachbartagen ab.
 */
class DimBlockpositionTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Nachtdienst am Kalendertag [d]: Wecker 19:30, Dienst 20:15 bis 06:45 am Folgetag. */
    private fun nachtdienst(d: Int, name: String = "Nachtschicht") = AlarmSlot(
        triggerTime = ep(2026, 9, d, 19, 30),
        shiftName = name,
        shiftEndTime = ep(2026, 9, d + 1, 6, 45)
    )

    private fun fenster(
        endeUhr: Int,
        positionen: Set<Blockposition> = Blockposition.ALLE
    ) = DimWindow(
        startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 0,
        endAnchor = DimAnchor.CLOCK, endClockMinutes = endeUhr * 60,
        blockPositionen = positionen
    )

    private fun spans(rule: DimRule, vararg slots: AlarmSlot, today: LocalDate = LocalDate.of(2026, 9, 18)) =
        DimWindowResolver.buildRuleSpans(
            alarms = slots.toList(),
            horizonDays = 14,
            today = today,
            zone = zone,
            ruleForShift = { name -> rule.takeIf { it.shiftPattern.equals(name, ignoreCase = true) } },
            ruleForFreeDay = { null }
        ).map { it.range }

    // --- die reine Positionsbestimmung ---

    @Test
    fun `Position aus Vortag und Folgetag`() {
        assertEquals(Blockposition.EINZELNER, DimWindowResolver.blockposition(vortag = false, folgetag = false))
        assertEquals(Blockposition.ERSTER, DimWindowResolver.blockposition(vortag = false, folgetag = true))
        assertEquals(Blockposition.MITTLERER, DimWindowResolver.blockposition(vortag = true, folgetag = true))
        assertEquals(Blockposition.LETZTER, DimWindowResolver.blockposition(vortag = true, folgetag = false))
    }

    // --- der Anlassfall ---

    @Test
    fun `Drei Nachtdienste - nach der letzten Nacht endet der Schlaf frueher`() {
        val rule = DimRule(
            name = "ND", shiftPattern = "Nachtschicht",
            windows = listOf(
                fenster(14, setOf(Blockposition.ERSTER, Blockposition.MITTLERER)),
                fenster(12, setOf(Blockposition.LETZTER, Blockposition.EINZELNER))
            )
        )

        val r = spans(rule, nachtdienst(18), nachtdienst(19), nachtdienst(20))

        assertEquals(
            listOf(
                ep(2026, 9, 19, 6, 45)..ep(2026, 9, 19, 14, 0), // nach Nacht 1 (Fr = erster Tag)
                ep(2026, 9, 20, 6, 45)..ep(2026, 9, 20, 14, 0), // nach Nacht 2 (Sa = mittlerer)
                ep(2026, 9, 21, 6, 45)..ep(2026, 9, 21, 12, 0)  // nach Nacht 3 (So = letzter)
            ),
            r
        )
    }

    @Test
    fun `Ein einzelner Nachtdienst ist EINZELNER - weder erster noch letzter`() {
        val nurErster = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(14, setOf(Blockposition.ERSTER))))
        val nurLetzter = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(12, setOf(Blockposition.LETZTER))))
        val einzeln = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(11, setOf(Blockposition.EINZELNER))))

        assertTrue(spans(nurErster, nachtdienst(18)).isEmpty())
        assertTrue(spans(nurLetzter, nachtdienst(18)).isEmpty())
        assertEquals(listOf(ep(2026, 9, 19, 6, 45)..ep(2026, 9, 19, 11, 0)), spans(einzeln, nachtdienst(18)))
    }

    @Test
    fun `Der Default ALLE laesst jedes bestehende Fenster unveraendert wirken`() {
        val rule = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(14)))
        assertEquals(3, spans(rule, nachtdienst(18), nachtdienst(19), nachtdienst(20)).size)
        assertEquals(1, spans(rule, nachtdienst(18)).size)
    }

    @Test
    fun `Leere Positionsmenge - das Fenster gilt nirgends`() {
        val rule = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(14, emptySet())))
        assertTrue(spans(rule, nachtdienst(18), nachtdienst(19)).isEmpty())
    }

    /**
     * Ein Block ist eine Folge DERSELBEN Schicht. Ein Frueh­dienst zwischen zwei Nachtdiensten
     * trennt sie: aus Fr-N, Sa-F, So-N werden zwei einzelne Nachtdienste.
     */
    @Test
    fun `Eine andere Schicht dazwischen unterbricht den Block`() {
        val nurEinzeln = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(11, setOf(Blockposition.EINZELNER))))
        val frueh = AlarmSlot(ep(2026, 9, 19, 5, 30), "Fruehschicht", ep(2026, 9, 19, 14, 12))

        val r = spans(nurEinzeln, nachtdienst(18), frueh, nachtdienst(20))

        assertEquals(2, r.size)
    }

    /**
     * Die Nachbarschaft wird ueber den Schichtnamen gebildet, und der ist ein frei eingegebener
     * Nutzertext - dieselbe Toleranz wie bei der Regelzuordnung (`findRuleForShift`).
     */
    @Test
    fun `Der Schichtname wird ohne Ruecksicht auf Gross- und Kleinschreibung verglichen`() {
        val rule = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(12, setOf(Blockposition.LETZTER))))

        val r = spans(rule, nachtdienst(18, "nachtschicht"), nachtdienst(19, "NACHTSCHICHT"))

        assertEquals(listOf(ep(2026, 9, 20, 6, 45)..ep(2026, 9, 20, 12, 0)), r)
    }

    /**
     * Der Vortag des ersten Horizont-Tages zaehlt mit: die Position von "heute" haengt daran, ob
     * GESTERN dieselbe Schicht war - und gestern liegt vor `today`, ausserhalb der Fenster-Schleife.
     * Genau dafuer haelt der ShiftSpanStore beendete Spannen laenger vor.
     */
    @Test
    fun `Der Vortag ausserhalb des Horizonts bestimmt die Position von heute mit`() {
        val nurLetzter = DimRule(name = "x", shiftPattern = "Nachtschicht", windows = listOf(fenster(12, setOf(Blockposition.LETZTER))))

        // Heute ist So 20.09.; Fr und Sa liegen davor. So ist der LETZTE Tag des Blocks.
        val r = spans(nurLetzter, nachtdienst(18), nachtdienst(19), nachtdienst(20), today = LocalDate.of(2026, 9, 20))

        assertEquals(listOf(ep(2026, 9, 21, 6, 45)..ep(2026, 9, 21, 12, 0)), r)
    }

    /** An freien Tagen gibt es keine Schicht und damit keine Position - das Feld wird ignoriert. */
    @Test
    fun `FREI-Regeln ignorieren die Blockposition`() {
        val frei = DimRule(
            name = "frei", shiftPattern = DimRule.SHIFT_FREE,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                    endAnchor = DimAnchor.CLOCK, endClockMinutes = 7 * 60,
                    blockPositionen = setOf(Blockposition.LETZTER)
                )
            )
        )
        val r = DimWindowResolver.buildRuleSpans(
            alarms = emptyList(), horizonDays = 2, today = LocalDate.of(2026, 9, 18), zone = zone,
            ruleForShift = { null }, ruleForFreeDay = { frei }
        )
        assertEquals(3, r.size) // Rueckblick-Tag + 2 Horizont-Tage
    }
}
