package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.FruehAus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Zeile „Nächster Wecker … → aus um …“ in der Auto-Aus-Karte (#143) und das Speichern der Wahl.
 *
 * Die Zeile MUSS dieselbe Minute nennen, auf die die Laufzeit den Bridge-Timer stellt - sonst
 * verspricht der Editor eine Uhrzeit, die nicht eintritt.
 */
class AutoAusUrteilTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val stand = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), berlin)
    private val tag = LocalDate.of(2026, 10, 14) // Daemmerung ~07:00, Sonnenaufgang ~07:31

    private fun ms(h: Int, m: Int) =
        LocalDateTime.of(tag, LocalTime.of(h, m)).atZone(berlin).toInstant().toEpochMilli()

    private fun form(
        minuten: Int = 60,
        ausBeiHell: HellAb = HellAb.DAEMMERUNG,
        autoOff: Boolean = true,
        modus: HueRuleModus = HueRuleModus.MANUELL,
        sunrise: SunriseConfig = SunriseConfig()
    ) = HueRuleFormState(
        name = "Morgen",
        shiftPattern = "Frühdienst",
        modus = modus,
        selectedGroupIds = setOf("5"),
        sunrise = sunrise,
        autoOffEnabled = autoOff,
        autoOffMinutes = minuten,
        ausBeiHell = ausBeiHell
    )

    private fun regel(f: HueRuleFormState) = f.toRule("r", emptyMap(), mapOf("5" to "Wohnzimmer"), emptyMap())

    @Test
    fun `gekuerzt - nennt dieselbe Minute wie der Bridge-Timer`() {
        val f = form(minuten = 120)
        val u = f.autoAusUrteil(ms(6, 30), stand)!!
        assertTrue(u.gekuerzt)
        assertFalse(u.schonHell)
        assertEquals(ms(8, 30), u.nachDauerUm)
        val timer = HueRuleUseCase.autoOffTargetsOf(listOf(regel(f)), FruehAus(ms(6, 30), ms(6, 30), stand)).single()
        assertEquals(u.ausUm, ms(6, 30) + timer.delayMinutes * 60_000L)
    }

    @Test
    fun `Dauer endet vor dem Hellwerden - nicht gekuerzt`() {
        val u = form(minuten = 10, ausBeiHell = HellAb.SONNENAUFGANG).autoAusUrteil(ms(6, 30), stand)!!
        assertFalse(u.gekuerzt)
        assertEquals(ms(6, 40), u.ausUm)
    }

    @Test
    fun `beim Wecken schon hell - die Dauer gilt`() {
        val u = form(minuten = 30).autoAusUrteil(ms(8, 0), stand)!!
        assertTrue(u.schonHell)
        assertEquals(ms(8, 30), u.ausUm)
    }

    @Test
    fun `Rampe ab Weckzeit - nie vor ihrem Ende`() {
        val rampe = SunriseConfig(enabled = true, durationMinutes = 30, startBeforeAlarm = false)
        val u = form(minuten = 60, modus = HueRuleModus.SONNENAUFGANG, sunrise = rampe).autoAusUrteil(ms(6, 50), stand)!!
        assertEquals(ms(7, 20), u.ausUm)
        assertEquals(ms(8, 20), u.nachDauerUm)
    }

    @Test
    fun `keine Zeile bei jeder Luecke`() {
        assertNull(form(ausBeiHell = HellAb.IMMER).autoAusUrteil(ms(6, 30), stand))
        assertNull(form(autoOff = false).autoAusUrteil(ms(6, 30), stand))
        assertNull(form().autoAusUrteil(null, stand))
        assertNull(form().autoAusUrteil(ms(6, 30), null))
        assertNull(form().autoAusUrteil(ms(6, 30), TageslichtStand.ohneOrt(berlin)))
    }

    @Test
    fun `gespeichert wird die Wahl nur mit Auto-Aus, und sie kommt zurueck`() {
        assertEquals(HellAb.DAEMMERUNG, regel(form()).ausBeiHell)
        assertEquals(HellAb.DAEMMERUNG, regel(form()).toFormState().ausBeiHell)
        assertEquals(HellAb.IMMER, regel(form(autoOff = false)).ausBeiHell)
    }
}
