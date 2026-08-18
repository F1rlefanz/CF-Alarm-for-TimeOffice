package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der DRITTE Fall der Schicht-Konfiguration: "nichts da, weil der Storage gesperrt war".
 *
 * WAS HIER ABGESICHERT WIRD (Befund 18.08.2026):
 * Ein Read auf einem CREDENTIAL-ENCRYPTED DataStore VOR der ersten Entsperrung wirft NICHT - er
 * liefert still leere Preferences und meldet Erfolg. Damit sah `shift_prefs` in einem
 * Direct-Boot-Prozess exakt aus wie "noch nie konfiguriert", und dieser Fall liefert die
 * STANDARDKONFIGURATION ALS ERFOLG. Zwei Folgen: die Alarm-Pipeline weckt zu Standardzeiten statt
 * zu den gepflegten, und das naechste Bearbeiten einer Schicht schreibt den Default persistent
 * ueber die echte Konfiguration (`ShiftUseCase` liest, kopiert eine Aenderung hinein, speichert
 * alles zurueck). Verschaerft dadurch, dass DataStore das leere Ergebnis fuer die restliche
 * PROZESSLAUFZEIT cacht - der Fehler heilte nicht mit dem Entsperren.
 *
 * Deshalb entscheidet `decodeShiftConfig` den Fall `raw == null` nicht mehr allein, sondern
 * zusammen mit `userUnlocked`.
 */
class ShiftConfigLockedStorageTest {

    // Exakt die Produktions-Konfiguration aus ShiftConfigRepository.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * DER REGRESSIONSFALL. Ohne die Unterscheidung waere das Ergebnis `NotConfigured` - und damit
     * die Standardkonfiguration als Erfolg.
     */
    @Test
    fun `fehlender Eintrag bei gesperrtem Storage ist KEIN NotConfigured`() {
        val decoded = decodeShiftConfig(json, null, userUnlocked = false)

        assertEquals(ShiftConfigDecodeResult.LockedStorage, decoded)
        assertTrue(
            "Ein gesperrter Storage darf nie als 'noch nie konfiguriert' gelten - genau daraus " +
                "entsteht der Default als Erfolg und anschliessend der Datenverlust",
            decoded !is ShiftConfigDecodeResult.Ok && decoded != ShiftConfigDecodeResult.NotConfigured
        )
    }

    /** Die Gegenprobe: bei entsperrtem Storage bleibt "nichts da" die harmlose Erstinstallation. */
    @Test
    fun `fehlender Eintrag bei entsperrtem Storage bleibt NotConfigured`() {
        assertEquals(
            ShiftConfigDecodeResult.NotConfigured,
            decodeShiftConfig(json, null, userUnlocked = true)
        )
    }

    /**
     * Vorhandene Daten schlagen die Sperr-Vermutung: was gelesen wurde, kann nicht aus einem
     * unlesbaren Store stammen. Sonst wuerde ein irrender `UserManager` eine intakte
     * Konfiguration entwerten.
     */
    @Test
    fun `vorhandene Konfiguration wird auch bei gemeldeter Sperre gelesen`() {
        val raw = json.encodeToString(ShiftConfig.getDefaultConfig())

        val decoded = decodeShiftConfig(json, raw, userUnlocked = false)

        assertTrue(decoded is ShiftConfigDecodeResult.Ok)
    }

    /** Eine defekte Konfiguration bleibt defekt - die Sperre verdeckt sie nicht. */
    @Test
    fun `defekte Konfiguration bleibt bei gemeldeter Sperre Broken`() {
        val decoded = decodeShiftConfig(json, "{kein gueltiges json", userUnlocked = false)

        assertTrue(decoded is ShiftConfigDecodeResult.Broken)
    }

    /** Der Default des Parameters haelt reine Dekodier-Aufrufer unveraendert. */
    @Test
    fun `ohne Angabe gilt entsperrt`() {
        assertEquals(ShiftConfigDecodeResult.NotConfigured, decodeShiftConfig(json, null))
    }
}
