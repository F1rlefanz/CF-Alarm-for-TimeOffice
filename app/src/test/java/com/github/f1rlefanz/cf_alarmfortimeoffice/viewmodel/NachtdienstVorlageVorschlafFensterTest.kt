package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.Companion.baueVorlagenRegel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.SchnellstartVorlage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regressionsschutz fuer #151: das Vorschlaf-Fenster der Nachtdienst-Vorlage (CLOCK 16:00 ->
 * ALARM+0) loeste bei einer Weckzeit VOR 16:00 auf 16:00 des VORTAGS auf (clockAtOrBefore) und
 * dimmte ~21,5 h am Stueck. Soll: an so einem Tag entfaellt das Fenster, die uebrigen Fenster der
 * Vorlage bleiben. Nutzt die echte Vorlage ([baueVorlagenRegel]) und den echten Aufloeser.
 */
class NachtdienstVorlageVorschlafFensterTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val regel = baueVorlagenRegel(SchnellstartVorlage.NACHTDIENST_RHYTHMUS, "x", "ND")!!
    private val vorschlaf = regel.windows.single { it.startAnchor == DimAnchor.CLOCK }

    @Test
    fun `a - Weckzeit 19_30 - Vorschlaf 16_00 bis 19_30 am selben Tag`() {
        val alarm = ep(2026, 10, 20, 19, 30)
        val r = DimWindowResolver.resolveShiftWindow(vorschlaf, alarm, 0, zone)!!
        assertEquals(ep(2026, 10, 20, 16, 0), r.first)
        assertEquals(alarm, r.last)
    }

    @Test
    fun `b - Weckzeit 13_30 - Vorschlaf entfaellt statt am Vortag zu beginnen`() {
        val alarm = ep(2026, 10, 20, 13, 30)
        val r = DimWindowResolver.resolveShiftWindow(vorschlaf, alarm, 0, zone)
        assertNull(
            "Vorschlaf aufgeloest als ${r?.let { java.time.Instant.ofEpochMilli(it.first) }} .. " +
                "${r?.let { java.time.Instant.ofEpochMilli(it.last) }}",
            r
        )
    }

    @Test
    fun `b2 - Weckzeit 13_30 end-to-end ueber buildRuleSpans - nur der Schlaf nach dem Dienst bleibt`() {
        val alarm = ep(2026, 10, 20, 13, 30)
        val schichtEnde = ep(2026, 10, 21, 6, 45)
        val spans = DimWindowResolver.buildRuleSpans(
            alarms = listOf(DimWindowResolver.AlarmSlot(alarm, "ND", schichtEnde)),
            horizonDays = 3,
            today = LocalDate.of(2026, 10, 19),
            zone = zone,
            ruleForShift = { if (it == "ND") regel else null },
            ruleForFreeDay = { null }
        )
        val zuLang = spans.filter { (it.range.last - it.range.first) > 12 * 3_600_000L }
        assertTrue(
            "Spannen > 12 h: " + zuLang.joinToString {
                "${java.time.Instant.ofEpochMilli(it.range.first)} .. ${java.time.Instant.ofEpochMilli(it.range.last)}"
            },
            zuLang.isEmpty()
        )
        // Der Vorschlaf entfaellt, der Schlaf nach dem Dienst (ab Schichtende) bleibt: ohne diese
        // Pruefung bestuende auch ein Fix, der die ganze Regel verwirft.
        assertEquals(schichtEnde, spans.single().range.first)
    }
}
