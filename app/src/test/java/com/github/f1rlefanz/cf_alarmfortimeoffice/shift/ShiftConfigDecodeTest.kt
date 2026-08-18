@file:Suppress("UnusedImport") // encodeToString/decodeFromString werden reified genutzt

package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeParseException

/**
 * Tests fuer [decodeShiftConfig] - die eine Stelle, an der entschieden wird, ob eine
 * Schicht-Konfiguration fehlt oder DEFEKT ist.
 *
 * Warum diese Unterscheidung existieren muss: vorher lieferten BEIDE Faelle
 * `ShiftConfig.getDefaultConfig()` als Erfolg. Folge war doppelter Schaden - die Alarm-Pipeline
 * weckte zu den STANDARD-Zeiten statt zu den gepflegten, und das naechste Bearbeiten einer
 * Schicht schrieb den Default persistent ueber die echte Konfiguration
 * (`ShiftUseCase.updateShiftDefinition()` liest, kopiert eine Aenderung hinein, speichert alles).
 *
 * Zusaetzlich abgesichert: gefangen wird [Exception], nicht nur `SerializationException`. Der
 * projekteigene `LocalTimeSerializer` wirft bei einem nicht parsbaren `HH:mm`-Wert eine
 * [DateTimeParseException] (eine `RuntimeException`), die sonst ungefangen aus dem
 * `shiftConfig`-Flow herausgelaufen waere und die App beim Oeffnen des Dimmer-/DND-Tabs beendet
 * haette (die Konsumenten haengen ihn per `stateIn(viewModelScope, ...)` auf).
 */
class ShiftConfigDecodeTest {

    // Exakt die Produktions-Konfiguration aus ShiftConfigRepository.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `fehlender Eintrag heisst NotConfigured (nicht defekt)`() {
        assertEquals(ShiftConfigDecodeResult.NotConfigured, decodeShiftConfig(json, null))
    }

    @Test
    fun `gueltiges JSON wird als Ok mit der echten Konfiguration dekodiert`() {
        val original = ShiftConfig.getDefaultConfig().copy(autoAlarmEnabled = false)
        val raw = json.encodeToString(original)

        val decoded = decodeShiftConfig(json, raw)

        assertTrue(decoded is ShiftConfigDecodeResult.Ok)
        assertEquals(original, (decoded as ShiftConfigDecodeResult.Ok).config)
    }

    @Test
    fun `abgeschnittenes JSON ist Broken - nicht stillschweigend der Standard`() {
        val raw = json.encodeToString(ShiftConfig.getDefaultConfig()).take(30)

        val decoded = decodeShiftConfig(json, raw)

        assertTrue("Defekt darf niemals als Erfolg mit Default durchgehen", decoded is ShiftConfigDecodeResult.Broken)
        assertEquals(raw, (decoded as ShiftConfigDecodeResult.Broken).raw)
    }

    @Test
    fun `unparsbare Weckzeit ist Broken und wirft nicht durch`() {
        // DateTimeParseException aus LocalTimeSerializer - keine SerializationException!
        val raw = json.encodeToString(ShiftConfig.getDefaultConfig())
            .replaceFirst(Regex("\"\\d{2}:\\d{2}\""), "\"kaputt\"")

        val decoded = decodeShiftConfig(json, raw)

        assertTrue(decoded is ShiftConfigDecodeResult.Broken)
        assertTrue(
            "Die Ursache muss der Zeit-Parse-Fehler sein",
            (decoded as ShiftConfigDecodeResult.Broken).cause is DateTimeParseException
        )
    }
}
