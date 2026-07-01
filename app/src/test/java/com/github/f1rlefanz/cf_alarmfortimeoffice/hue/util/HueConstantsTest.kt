package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echte Unit-Tests für die reinen Berechnungs-/Validierungsfunktionen in
 * [HueConstants.Utils] und [HueConstants.Validation].
 *
 * Alle Erwartungswerte wurden von Hand nachgerechnet (Float-Arithmetik gefolgt
 * von .toInt()-TRUNKIERUNG, nicht Rundung – wie in der Produktion).
 */
class HueConstantsTest {

    // ---- percentageToBrightness: Prozent (0-100) -> Hue-Helligkeit (1-254) ----

    @Test
    fun `percentageToBrightness bei 0 Prozent liefert MIN_BRIGHTNESS`() {
        assertEquals(1, HueConstants.Utils.percentageToBrightness(0))
    }

    @Test
    fun `percentageToBrightness bei 100 Prozent liefert MAX_BRIGHTNESS`() {
        // (100/100.0) * 253 + 1 = 254.0 -> toInt() = 254
        assertEquals(254, HueConstants.Utils.percentageToBrightness(100))
    }

    @Test
    fun `percentageToBrightness bei 50 Prozent rundet per Trunkierung ab`() {
        // (50/100.0)*253 + 1 = 127.5 -> toInt() trunkiert auf 127 (KEINE Rundung!)
        assertEquals(127, HueConstants.Utils.percentageToBrightness(50))
    }

    @Test
    fun `percentageToBrightness bei 33 Prozent`() {
        // (33/100.0)*253 + 1 = 84.49 -> toInt() = 84
        assertEquals(84, HueConstants.Utils.percentageToBrightness(33))
    }

    @Test
    fun `percentageToBrightness clampt negative Werte auf 0 Prozent`() {
        assertEquals(1, HueConstants.Utils.percentageToBrightness(-10))
    }

    @Test
    fun `percentageToBrightness clampt Werte ueber 100 auf MAX_BRIGHTNESS`() {
        assertEquals(254, HueConstants.Utils.percentageToBrightness(150))
    }

    // ---- brightnessToPercentage: Hue-Helligkeit (1-254) -> Prozent (0-100) ----

    @Test
    fun `brightnessToPercentage bei MIN_BRIGHTNESS liefert 0 Prozent`() {
        assertEquals(0, HueConstants.Utils.brightnessToPercentage(1))
    }

    @Test
    fun `brightnessToPercentage bei MAX_BRIGHTNESS liefert 100 Prozent`() {
        assertEquals(100, HueConstants.Utils.brightnessToPercentage(254))
    }

    @Test
    fun `brightnessToPercentage bei 127 trunkiert ab`() {
        // (127-1)/253 * 100 = 49.802... -> toInt() = 49
        assertEquals(49, HueConstants.Utils.brightnessToPercentage(127))
    }

    @Test
    fun `brightnessToPercentage clampt Werte unter MIN_BRIGHTNESS`() {
        // 0 wird auf MIN_BRIGHTNESS=1 geclamped -> 0 Prozent
        assertEquals(0, HueConstants.Utils.brightnessToPercentage(0))
    }

    @Test
    fun `brightnessToPercentage clampt Werte ueber MAX_BRIGHTNESS`() {
        assertEquals(100, HueConstants.Utils.brightnessToPercentage(300))
    }

    @Test
    fun `percentageToBrightness und brightnessToPercentage Rundtrip fuer 0 und 100 Prozent`() {
        assertEquals(0, HueConstants.Utils.brightnessToPercentage(HueConstants.Utils.percentageToBrightness(0)))
        assertEquals(100, HueConstants.Utils.brightnessToPercentage(HueConstants.Utils.percentageToBrightness(100)))
    }

    // ---- clampBrightness / clampHue / clampSaturation / clampColorTemperature ----

    @Test
    fun `clampBrightness begrenzt auf 1 bis 254`() {
        assertEquals(1, HueConstants.Utils.clampBrightness(-5))
        assertEquals(254, HueConstants.Utils.clampBrightness(1000))
        assertEquals(127, HueConstants.Utils.clampBrightness(127))
    }

    @Test
    fun `clampHue begrenzt auf 0 bis 65535`() {
        assertEquals(0, HueConstants.Utils.clampHue(-1))
        assertEquals(65535, HueConstants.Utils.clampHue(100_000))
        assertEquals(30000, HueConstants.Utils.clampHue(30000))
    }

    @Test
    fun `clampSaturation begrenzt auf 0 bis 254`() {
        assertEquals(0, HueConstants.Utils.clampSaturation(-1))
        assertEquals(254, HueConstants.Utils.clampSaturation(999))
    }

    @Test
    fun `clampColorTemperature begrenzt auf 153 bis 500`() {
        assertEquals(153, HueConstants.Utils.clampColorTemperature(100))
        assertEquals(500, HueConstants.Utils.clampColorTemperature(600))
        assertEquals(300, HueConstants.Utils.clampColorTemperature(300))
    }

    // ---- Validation ----

    @Test
    fun `isValidBrightness akzeptiert Grenzwerte und lehnt ausserhalb liegende Werte ab`() {
        assertTrue(HueConstants.Validation.isValidBrightness(1))
        assertTrue(HueConstants.Validation.isValidBrightness(254))
        assertFalse(HueConstants.Validation.isValidBrightness(0))
        assertFalse(HueConstants.Validation.isValidBrightness(255))
    }

    @Test
    fun `isValidHue akzeptiert Grenzwerte und lehnt ausserhalb liegende Werte ab`() {
        assertTrue(HueConstants.Validation.isValidHue(0))
        assertTrue(HueConstants.Validation.isValidHue(65535))
        assertFalse(HueConstants.Validation.isValidHue(-1))
        assertFalse(HueConstants.Validation.isValidHue(65536))
    }

    @Test
    fun `isValidColorTemperature akzeptiert Grenzwerte und lehnt ausserhalb liegende Werte ab`() {
        assertTrue(HueConstants.Validation.isValidColorTemperature(153))
        assertTrue(HueConstants.Validation.isValidColorTemperature(500))
        assertFalse(HueConstants.Validation.isValidColorTemperature(152))
        assertFalse(HueConstants.Validation.isValidColorTemperature(501))
    }

    @Test
    fun `isValidXY akzeptiert nur Werte zwischen 0 und 1`() {
        assertTrue(HueConstants.Validation.isValidXY(0.0f, 1.0f))
        assertTrue(HueConstants.Validation.isValidXY(0.3127f, 0.3290f))
        assertFalse(HueConstants.Validation.isValidXY(-0.1f, 0.5f))
        assertFalse(HueConstants.Validation.isValidXY(0.5f, 1.1f))
    }
}
