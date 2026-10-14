package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ein geplanter Vorab-Sonnenaufgang prueft beim Feuern, ob sein Wecker noch steht.
 *
 * Hergang (Krankheitstag-Pruefung, 14.10.2026): „Dienst fällt aus“ loescht den Wecker; den
 * `SunriseStartWorker` raeumte nur der Alarm-Beobachter ab - entprellt und nur im lebenden Prozess.
 * Ohne diese Pruefung fuhr das Licht am freien Morgen hoch und blieb ohne Auto-Aus an.
 */
class HueSonnenaufgangGedecktTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val weckzeit = LocalDateTime.of(2026, 10, 15, 5, 0)

    private fun alarm(
        name: String = "Frühdienst",
        zeit: LocalDateTime = weckzeit,
        isActive: Boolean = true,
        isSilent: Boolean = false
    ) = AlarmInfo(
        id = 1,
        shiftId = "s",
        shiftName = name,
        triggerTime = zeit.atZone(zone).toInstant().toEpochMilli(),
        formattedTime = "05:00",
        isActive = isActive,
        isSilent = isSilent
    )

    private fun gedeckt(vararg alarme: AlarmInfo) =
        HueSmartScheduler.sonnenaufgangNochGedeckt(alarme.toList(), "Frühdienst", weckzeit, zone)

    @Test
    fun `der Wecker steht noch - die Rampe laeuft`() {
        assertTrue(gedeckt(alarm()))
    }

    @Test
    fun `der Wecker ist weg - Dienst faellt aus oder Nicht wecken - keine Rampe`() {
        assertFalse(gedeckt())
        // Ein anderer Wecker am selben Tag deckt ihn nicht.
        assertFalse(gedeckt(alarm(zeit = weckzeit.plusHours(1))))
        assertFalse(gedeckt(alarm(name = "Spätdienst")))
    }

    @Test
    fun `inaktiv oder still deckt ihn nicht - dieselben Bedingungen wie beim Planen`() {
        assertFalse(gedeckt(alarm(isActive = false)))
        assertFalse(gedeckt(alarm(isSilent = true)))
    }
}
