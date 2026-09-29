package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Charakterisiert den V1-Zustandskoerper, den controlLight UND controlGroup senden
 * (buildStateChange): Schluessel, Werte, Reihenfolge und das Verwerfen ungueltiger Werte.
 */
class BuildStateChangeTest {

    private fun build(
        on: Boolean? = null,
        brightness: Int? = null,
        hue: Int? = null,
        saturation: Int? = null,
        colorTemperature: Int? = null,
        transitionTime: Int? = null,
        alert: String? = null
    ) = buildStateChange(
        on = on,
        brightness = brightness,
        hue = hue,
        saturation = saturation,
        colorTemperature = colorTemperature,
        transitionTime = transitionTime,
        alert = alert
    )

    @Test
    fun `ohne Angaben entsteht eine leere Map`() {
        assertTrue(build().isEmpty())
    }

    @Test
    fun `an und aus werden als on gesendet`() {
        assertEquals(mapOf("on" to true), build(on = true))
        assertEquals(mapOf("on" to false), build(on = false))
    }

    @Test
    fun `Helligkeit an den Bereichsgrenzen wird uebernommen`() {
        assertEquals(mapOf("bri" to 0), build(brightness = 0))
        assertEquals(mapOf("bri" to 254), build(brightness = 254))
    }

    @Test
    fun `Farbe als hue und sat`() {
        assertEquals(mapOf("hue" to 46920, "sat" to 254), build(hue = 46920, saturation = 254))
    }

    @Test
    fun `Farbtemperatur innerhalb der Mired-Grenzen`() {
        assertEquals(mapOf("ct" to 153), build(colorTemperature = 153))
        assertEquals(mapOf("ct" to 500), build(colorTemperature = 500))
    }

    @Test
    fun `Uebergang und Alert`() {
        assertEquals(mapOf("transitiontime" to 0), build(transitionTime = 0))
        assertEquals(mapOf("transitiontime" to 65535), build(transitionTime = 65535))
        assertEquals(mapOf("alert" to "select"), build(alert = "select"))
    }

    @Test
    fun `Werte ausserhalb des Bereichs werden verworfen`() {
        val result = build(
            on = true,
            brightness = 255,
            hue = 65536,
            saturation = -1,
            colorTemperature = 152,
            transitionTime = 65536
        )
        assertEquals(mapOf("on" to true), result)
        assertTrue(build(colorTemperature = 501).isEmpty())
    }

    @Test
    fun `alle Felder in fester Reihenfolge`() {
        val result = build(
            on = true,
            brightness = 1,
            hue = 2,
            saturation = 3,
            colorTemperature = 300,
            transitionTime = 4,
            alert = "none"
        )
        assertEquals(
            listOf("on", "bri", "hue", "sat", "ct", "transitiontime", "alert"),
            result.keys.toList()
        )
        assertEquals(listOf<Any>(true, 1, 2, 3, 300, 4, "none"), result.values.toList())
    }
}
