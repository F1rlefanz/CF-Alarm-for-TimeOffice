package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deckt die Rasterung ab, die aus dem Wind-down-Slider in DimmerTabContent herausgezogen wurde.
 *
 * Der Slider meldete seinen Wert bis dahin bei JEDEM Touch-Frame an das ViewModel (kein
 * `onValueChangeFinished`) und stiess damit pro Frame die komplette Dimmer-Neuplanung an. Beim
 * Umbau auf "erst beim Loslassen melden" wandert die Rasterung in den lokalen Zustand - sie muss
 * dabei exakt dieselben Werte liefern wie vorher `(raw / 15).toInt() * 15`.
 */
class SliderQuantizeTest {

    @Test
    fun `rastet auf Vielfache von 15 ab wie der frühere Inline-Ausdruck`() {
        assertEquals(0, quantizeToStep(0f, 15))
        assertEquals(0, quantizeToStep(14.9f, 15))
        assertEquals(15, quantizeToStep(15f, 15))
        assertEquals(15, quantizeToStep(29.9f, 15))
        assertEquals(30, quantizeToStep(30.4f, 15))
        assertEquals(120, quantizeToStep(120.99f, 15))
    }

    @Test
    fun `Schrittweite 1 schneidet nur ab - unveraendertes Verhalten der Staerke- und Waerme-Regler`() {
        assertEquals(0, quantizeToStep(0.4f, 1))
        assertEquals(7, quantizeToStep(7.9f, 1))
        assertEquals(
            DimOverlayPrefs.STRENGTH_MAX,
            quantizeToStep(DimOverlayPrefs.STRENGTH_MAX.toFloat(), 1)
        )
    }

    @Test
    fun `Ergebnis bleibt im Wind-down-Bereich der Prefs`() {
        val min = DimOverlayPrefs.WINDDOWN_MIN_LIMIT
        val max = DimOverlayPrefs.WINDDOWN_MAX_LIMIT
        var value = min.toFloat()
        while (value <= max.toFloat()) {
            val quantized = quantizeToStep(value, 15)
            assertTrue("$quantized liegt unter dem Minimum $min", quantized >= 0)
            assertTrue("$quantized liegt ueber dem Maximum $max", quantized <= max)
            value += 0.7f
        }
    }
}
