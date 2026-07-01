package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echte Unit-Tests für die Farbraum-Mathematik in [HueColorConverter].
 *
 * Alle Erwartungswerte wurden von Hand (bzw. per Float32-Nachrechnung der
 * exakt gleichen Formel-Reihenfolge wie in der Produktion) berechnet, NICHT
 * geraten. Besonders die xy-Gamut-Correction und die HSV-Rundung sind
 * numerisch heikel (Float-Rundungsfehler), daher wird dort mit Toleranz
 * (delta) statt exakter Gleichheit verglichen, wo sinnvoll.
 */
class HueColorConverterTest {

    // ---- rgbToHueColor: reine Primärfarben liegen exakt auf den Gamut-B-Eckpunkten ----

    @Test
    fun `rgbToHueColor Rot liefert hue 0 und volle Saettigung`() {
        val result = HueColorConverter.rgbToHueColor(255, 0, 0)

        assertEquals(0, result.hue)
        assertEquals(254, result.saturation)
        assertEquals("#FF0000", result.rgb)
        // Reines Rot liegt exakt auf dem Gamut-B-Rotpunkt (0.675, 0.322) -> keine Gamut-Korrektur
        assertEquals(0.675f, result.xy!![0], 0.0001f)
        assertEquals(0.322f, result.xy!![1], 0.0001f)
    }

    @Test
    fun `rgbToHueColor Gruen liefert hue 21845 (120 Grad)`() {
        val result = HueColorConverter.rgbToHueColor(0, 255, 0)

        // 120 Grad * 65535 / 360 = 21845.0 exakt
        assertEquals(21845, result.hue)
        assertEquals(254, result.saturation)
        assertEquals(0.4091f, result.xy!![0], 0.0001f)
        assertEquals(0.518f, result.xy!![1], 0.0001f)
    }

    @Test
    fun `rgbToHueColor Blau liefert hue 43690 (240 Grad)`() {
        val result = HueColorConverter.rgbToHueColor(0, 0, 255)

        // 240 Grad * 65535 / 360 = 43690.0 exakt
        assertEquals(43690, result.hue)
        assertEquals(254, result.saturation)
        assertEquals(0.167f, result.xy!![0], 0.0001f)
        assertEquals(0.04f, result.xy!![1], 0.0001f)
    }

    @Test
    fun `rgbToHueColor Weiss liefert Saettigung 0 und xy aus der sRGB-Matrix ohne Gamut-Korrektur`() {
        // r=g=b=1.0 -> gammaCorrection(1.0) = 1.0 fuer alle Kanaele (Wert > 0.04045-Schwelle,
        // ((1+0.055)/1.055)^2.4 = 1.0). Die sRGB->XYZ-Matrix liefert dann direkt die
        // Summen X=0.98086303, Y=0.99999905, Z=1.05843699 (Float32, von Hand nachgerechnet),
        // daraus xy = (X/S, Y/S) = (0.32273, 0.32902) mit S=X+Y+Z.
        // Dieser Punkt liegt INNERHALB des Gamut-B-Dreiecks (s1=0.1029>=0, s2=0.0672>=0,
        // s1+s2=0.1701 <= Schwelle 0.1746) -> KEINE Gamut-Korrektur noetig.
        val result = HueColorConverter.rgbToHueColor(255, 255, 255)

        assertEquals(0, result.hue)
        assertEquals(0, result.saturation)
        assertEquals("#FFFFFF", result.rgb)
        assertEquals(0.32273f, result.xy!![0], 0.001f)
        assertEquals(0.32902f, result.xy!![1], 0.001f)
    }

    @Test
    fun `rgbToHueColor Schwarz liefert hue 0 und Saettigung 0 mit D65-Weisspunkt-Fallback`() {
        // r=g=b=0 -> delta=0 -> hue=0, max=0 -> sat=0.
        // In rgbToXy: r=g=b=0 -> Summe x+y+z=0 -> Fallback auf D65-Weißpunkt (0.3127, 0.3290)
        val result = HueColorConverter.rgbToHueColor(0, 0, 0)

        assertEquals(0, result.hue)
        assertEquals(0, result.saturation)
        assertEquals(0.3127f, result.xy!![0], 0.0001f)
        assertEquals(0.329f, result.xy!![1], 0.0001f)
    }

    @Test
    fun `rgbToHueColor Orange wird korrekt umgerechnet und gamut-korrigiert`() {
        // RGB(255,165,0): max=r=1.0, min=b=0.0, delta=1.0 -> hue = 60*((g-b)/delta mod 6)
        // = 60 * (0.64705884 mod 6) = 38.823532 Grad, saturation = delta/max = 1.0 (max Saettigung).
        // hue*65535/360 = 7067.5005 (Float32) -> roundToInt (round-half-up) = 7068.
        val result = HueColorConverter.rgbToHueColor(255, 165, 0)

        assertEquals(7068, result.hue)
        assertEquals(254, result.saturation)
        // Rohes xy (0.56220, 0.41656) liegt außerhalb des Gamut-B-Dreiecks (isInGamut=false)
        // und wird auf die naeheste Kante (Rot-Gruen) projiziert (von Hand nachgerechnet:
        // Distanz zur RG-Kante 0.00919 ist die kleinste der drei Kanten-Distanzen).
        assertEquals(0.5568f, result.xy!![0], 0.001f)
        assertEquals(0.4092f, result.xy!![1], 0.001f)
    }

    // ---- hueColorToRgb: RGB-Hex hat Vorrang vor xy/HSV ----

    @Test
    fun `hueColorToRgb bevorzugt RGB-Hex wenn vorhanden`() {
        val hueColor = HueColor(hue = 12345, saturation = 100, xy = listOf(0.1f, 0.2f), rgb = "#112233")

        val (r, g, b) = HueColorConverter.hueColorToRgb(hueColor)

        assertEquals(0x11, r)
        assertEquals(0x22, g)
        assertEquals(0x33, b)
    }

    @Test
    fun `hueColorToRgb nutzt xy wenn kein RGB-Hex vorhanden ist`() {
        // xy = reiner Rotpunkt des Gamuts (0.675, 0.322) -> erwartete RGB (255, 116, 0) nachgerechnet
        val hueColor = HueColor(hue = null, saturation = null, xy = listOf(0.675f, 0.322f), rgb = null)

        val (r, g, b) = HueColorConverter.hueColorToRgb(hueColor)

        assertEquals(255, r)
        assertEquals(116, g)
        assertEquals(0, b)
    }

    @Test
    fun `hueColorToRgb nutzt HSV-Fallback wenn weder RGB-Hex noch xy vorhanden sind`() {
        // hue=0, saturation=254 (voll gesättigt), value wird intern als 1.0 angenommen -> reines Rot
        val hueColor = HueColor(hue = 0, saturation = 254, xy = null, rgb = null)

        val (r, g, b) = HueColorConverter.hueColorToRgb(hueColor)

        assertEquals(255, r)
        assertEquals(0, g)
        assertEquals(0, b)
    }

    @Test
    fun `hueColorToRgb mit Saettigung 0 ergibt Weiss`() {
        val hueColor = HueColor(hue = 0, saturation = 0, xy = null, rgb = null)

        val (r, g, b) = HueColorConverter.hueColorToRgb(hueColor)

        assertEquals(255, r)
        assertEquals(255, g)
        assertEquals(255, b)
    }

    @Test
    fun `hueColorToRgb mit hue 21845 und voller Saettigung ergibt Gruen`() {
        // 21845 * 360 / 65535 = 120.0 Grad exakt -> reines Grün
        val hueColor = HueColor(hue = 21845, saturation = 254, xy = null, rgb = null)

        val (r, g, b) = HueColorConverter.hueColorToRgb(hueColor)

        assertEquals(0, r)
        assertEquals(255, g)
        assertEquals(0, b)
    }

    // ---- Farbtemperatur-Konvertierung (Kelvin <-> Mired) ----

    @Test
    fun `kelvinToHueMireds rechnet 2700K in 370 Mired um`() {
        // 1_000_000 / 2700 = 370 (Ganzzahldivision, abgerundet)
        assertEquals(370, HueColorConverter.kelvinToHueMireds(2700))
    }

    @Test
    fun `kelvinToHueMireds clampt auf 6500K obere Grenze`() {
        // 10000K wird auf 6500K geclamped -> 1_000_000/6500 = 153 (abgerundet), Untergrenze MIN=153
        assertEquals(153, HueColorConverter.kelvinToHueMireds(10_000))
    }

    @Test
    fun `kelvinToHueMireds clampt auf 2000K untere Grenze`() {
        // 500K wird auf 2000K geclamped -> 1_000_000/2000 = 500 = MAX_COLOR_TEMPERATURE
        assertEquals(500, HueColorConverter.kelvinToHueMireds(500))
    }

    @Test
    fun `hueMiredsToKelvin rechnet 370 Mired in 2702K um`() {
        // 1_000_000 / 370 = 2702 (Ganzzahldivision, abgerundet)
        assertEquals(2702, HueColorConverter.hueMiredsToKelvin(370))
    }

    @Test
    fun `hueMiredsToKelvin clampt Mired-Werte in gueltigen Bereich`() {
        // 50 Mired liegt unter MIN_COLOR_TEMPERATURE (153) -> wird auf 153 geclamped -> 1_000_000/153 = 6535
        assertEquals(6535, HueColorConverter.hueMiredsToKelvin(50))
        // 1000 Mired liegt über MAX_COLOR_TEMPERATURE (500) -> wird auf 500 geclamped -> 1_000_000/500 = 2000
        assertEquals(2000, HueColorConverter.hueMiredsToKelvin(1000))
    }

    @Test
    fun `Kelvin-Mired Rundtrip ist ungefaehr stabil`() {
        val originalKelvin = 4000
        val mireds = HueColorConverter.kelvinToHueMireds(originalKelvin)
        val roundTripped = HueColorConverter.hueMiredsToKelvin(mireds)

        // 1_000_000/4000 = 250 Mired, 1_000_000/250 = 4000K -> exakter Rundtrip in diesem Fall
        assertEquals(250, mireds)
        assertEquals(4000, roundTripped)
    }

    // ---- Farbpresets ----

    @Test
    fun `getPresetColor liefert fuer WARM_WHITE feste xy-Werte ohne Neuberechnung`() {
        val preset = HueColorConverter.getPresetColor(HueColorConverter.ColorPreset.WARM_WHITE)

        assertEquals(0, preset.hue)
        assertEquals(0, preset.saturation)
        assertEquals(listOf(0.4573f, 0.4100f), preset.xy)
        assertEquals("#FFB46B", preset.rgb)
    }

    @Test
    fun `getPresetColor RED delegiert an rgbToHueColor(255,0,0)`() {
        val preset = HueColorConverter.getPresetColor(HueColorConverter.ColorPreset.RED)
        val direct = HueColorConverter.rgbToHueColor(255, 0, 0)

        assertEquals(direct, preset)
    }

    @Test
    fun `alle ColorPreset-Werte liefern ein gueltiges HueColor ohne Exception`() {
        HueColorConverter.ColorPreset.entries.forEach { preset ->
            val color = HueColorConverter.getPresetColor(preset)
            assertTrue("hue muss im gueltigen Bereich liegen fuer $preset", (color.hue ?: 0) in 0..65535)
            assertTrue("saturation muss im gueltigen Bereich liegen fuer $preset", (color.saturation ?: 0) in 0..254)
        }
    }
}
