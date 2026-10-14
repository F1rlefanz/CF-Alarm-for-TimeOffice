package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Die Etiketten der Tagesliste (#139) tragen kleinen Text (labelMedium) auf einer GEFUELLTEN
 * Flaeche. WCAG AA verlangt dafuer 4,5:1. Die Ampelfarben selbst reichen auf ihrer weichen Flaeche
 * nicht (SuccessGreen auf SuccessContainerLight ~4,4:1, WarningAmber ~3,6:1) - deshalb die eigenen
 * dunkleren Textfarben im hellen Thema. Dieser Test haelt das fest.
 */
class ThemeKontrastTest {

    private fun kontrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAA(name: String, text: Color, flaeche: Color) {
        val k = kontrast(text, flaeche)
        assertTrue("$name: Kontrast %.2f < 4,5".format(k), k >= 4.5)
    }

    @Test
    fun `jeder Status-Ton erreicht AA in hell und dunkel`() {
        StatusTon.entries.forEach { ton ->
            listOf(false, true).forEach { dunkel ->
                val paar = tonPaar(ton, dunkel)
                assertAA("$ton dunkel=$dunkel", paar.text, paar.flaeche)
            }
        }
    }

    @Test
    fun `die neutralen Etiketten auf surfaceContainer erreichen AA`() {
        listOf("hell" to LightColorScheme, "dunkel" to DarkColorScheme).forEach { (name, schema) ->
            assertAA("gedaempft $name", schema.onSurfaceVariant, schema.surfaceContainer)
            assertAA("Wirkung $name", schema.onSurface, schema.surfaceContainer)
        }
    }

    @Test
    fun `die Ampelfarbe allein reicht auf ihrer weichen Flaeche nicht - der Grund fuer die eigenen Textfarben`() {
        // Wird dieser Test rot, ist die Sonderfarbe womoeglich ueberfluessig - dann aufraeumen.
        assertTrue(kontrast(SuccessGreen, SuccessContainerLight) < 4.5)
        assertTrue(kontrast(WarningAmber, WarningContainerLight) < 4.5)
    }
}
