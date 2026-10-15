package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.runtime.saveable.SaverScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Tageslicht-Bedingung (#140) im Formular des Hue-Editors: sie muss den Weg Formular -> Regel
 * -> Formular in JEDEM Modus ueberleben (sie gilt fuer alle drei, beim Sonnenaufgang fuer die ganze
 * Rampe), und die Rotation (Saver) darf sie nicht auf IMMER zuruecksetzen - sonst liefe eine Regel
 * nach dem Speichern wieder bei Tageslicht, ohne dass der Nutzer es aenderte.
 */
class HueRuleFormStateTageslichtTest {

    private val lightNames = mapOf("4" to "Deckenlampe")
    private val groupNames = mapOf("1" to "Wohnzimmer")

    private fun HueRuleFormState.baue() = toRule("rule_1", lightNames, groupNames, emptyMap())

    private fun formFuer(modus: HueRuleModus, hellAb: HellAb) = HueRuleFormState(
        name = "Frueh",
        shiftPattern = "Frühdienst",
        modus = modus,
        selectedLightIds = setOf("4"),
        szenen = listOf(SzenenAuswahl("s1", "Nachtlicht", "1", "Wohnzimmer")),
        hellAb = hellAb
    )

    @Test
    fun `hellAb ueberlebt den Rundlauf in jedem Modus`() {
        for (modus in HueRuleModus.entries) {
            for (wahl in HellAb.entries) {
                val regel = formFuer(modus, wahl).baue()
                assertEquals("toRule $modus/$wahl", wahl, regel.hellAb)
                assertEquals("toFormState $modus/$wahl", wahl, regel.toFormState().hellAb)
            }
        }
    }

    @Test
    fun `eine Bestandsregel ohne Bedingung oeffnet mit IMMER`() {
        val alt = HueScheduleRule(
            id = "r", name = "A", shiftPattern = "B",
            timeRanges = listOf(HueTimeRange(actions = emptyList()))
        )
        assertEquals(HellAb.IMMER, alt.toFormState().hellAb)
        assertEquals(HellAb.IMMER, HueRuleFormState().hellAb)
    }

    @Test
    fun `der Saver behaelt eine gesetzte Bedingung`() {
        val form = formFuer(HueRuleModus.SONNENAUFGANG, HellAb.DAEMMERUNG)
        val roh = with(HueRuleFormStateSaver) { SaverScope { true }.save(form) }
        val zurueck = HueRuleFormStateSaver.restore(roh!!)
        assertEquals(form, zurueck)
        assertEquals(HellAb.DAEMMERUNG, zurueck!!.hellAb)
    }
}
