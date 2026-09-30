package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echte Unit-Tests für die reinen Berechnungs-/Validierungsfunktionen in
 * [HueConstants.Utils] und [HueConstants.Validation].
 */
class HueConstantsTest {

    // ---- clampBrightness ----

    @Test
    fun `clampBrightness begrenzt auf 1 bis 254`() {
        assertEquals(1, HueConstants.Utils.clampBrightness(-5))
        assertEquals(254, HueConstants.Utils.clampBrightness(1000))
        assertEquals(127, HueConstants.Utils.clampBrightness(127))
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
}
