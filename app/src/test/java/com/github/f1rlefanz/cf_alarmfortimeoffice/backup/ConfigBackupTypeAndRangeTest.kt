package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests fuer die WERT-Pruefungen des Imports (der Schluessel-Filter steht in
 * [ConfigBackupFilterTest]).
 *
 * Eine Exportdatei ist Text: von Hand bearbeitbar, aus einer aelteren Version, unterwegs
 * beschaedigt. Der Schluesselname allein sagt nichts darueber, ob der WERT verwertbar ist.
 */
class ConfigBackupTypeAndRangeTest {

    /**
     * DER ERWARTETE TYP KOMMT VOM SCHLUESSEL, NICHT AUS DER DATEI.
     *
     * `applyValue` prueft nur, ob sich der Wert in den BEHAUPTETEN Typ parsen laesst - damit
     * entschied eine fremde Datei ueber den DataStore-Typ. Ein falsch typisierter Wert ist
     * schlimmer als ein fehlender: er liegt reboot-fest in der `preferences_pb`, und der naechste
     * Lesezugriff scheitert mit einer ClassCastException, BEVOR ein `?:`-Default oder `coerceIn`
     * greifen kann. Bei `snooze_minutes` als String hiesse das: `AlarmPrefs.snoozeMinutes` wirft
     * bei jedem Alarm-Feuern, der `AlarmReceiver` verschluckt es in seinem try/catch - der Wecker
     * bliebe stumm.
     */
    @Test
    fun `lokal bekannter Schluessel erzwingt seinen Typ`() {
        val prefs = mutablePreferencesOf(intPreferencesKey("snooze_minutes") to 5)

        assertNotNull(
            "String, wo lokal ein Int liegt, muss abgelehnt werden",
            ConfigBackupUseCase.typeMismatch(prefs, "snooze_minutes", "string")
        )
        assertNull(ConfigBackupUseCase.typeMismatch(prefs, "snooze_minutes", "int"))
    }

    /**
     * Auch OHNE lokalen Bestand urteilbar: fuer die zwei Zahlen-Schluessel, die zusaetzlich
     * bereichsgeprueft sind, steht der Typ fest. Sonst waere der Schutz genau bei einer frischen
     * Installation weg - dem Fall, fuer den der Import gebaut wurde.
     */
    @Test
    fun `bekannte Zahlen-Schluessel urteilen auch ohne lokalen Wert`() {
        val leer = mutablePreferencesOf()

        assertNotNull(ConfigBackupUseCase.typeMismatch(leer, "snooze_minutes", "string"))
        assertNotNull(ConfigBackupUseCase.typeMismatch(leer, "dnd_oncall_cutoff_min", "boolean"))
        assertNull(ConfigBackupUseCase.typeMismatch(leer, "snooze_minutes", "int"))
    }

    /**
     * Ein voellig unbekannter Schluessel (aus einer neueren Version) behaelt den Typ der Datei -
     * das ist die einzige verfuegbare Information, und er kann keinen bestehenden Leser
     * beschaedigen. Dieselbe Grundrichtung wie beim Schluessel-Filter: im Zweifel uebernehmen.
     */
    @Test
    fun `unbekannter Schluessel behaelt den Typ der Datei`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("irgendwas_altes") to "x")

        assertNull(ConfigBackupUseCase.typeMismatch(prefs, "voellig_neuer_schluessel", "boolean"))
    }

    /** Der lokale Typ gewinnt auch dann, wenn er nicht Int ist. */
    @Test
    fun `lokaler StringSet-Wert lehnt einen String aus der Datei ab`() {
        val prefs = mutablePreferencesOf(
            androidx.datastore.preferences.core.stringSetPreferencesKey("dnd_oncall_shifts") to setOf("AD1")
        )

        assertNotNull(ConfigBackupUseCase.typeMismatch(prefs, "dnd_oncall_shifts", "string"))
        assertNull(ConfigBackupUseCase.typeMismatch(prefs, "dnd_oncall_shifts", "stringSet"))
    }

    /**
     * Die Bereichspruefung des Weckton-Anstiegs.
     *
     * WARUM DIESE BEIDEN SCHLUESSEL: Sie bestimmen, wie leise und wie lange der Wecker anlaeuft.
     * Eine Datei mit `3600` Sekunden oder `1` Prozent ergibt einen Wecker, den man nicht hoert -
     * und beides sind fuer sich genommen voellig gueltige Zahlen, die keine Typpruefung und kein
     * `?:`-Default abfaengt. Der Lesepfad klemmt sie zwar zusaetzlich, aber nur hier erfaehrt der
     * Nutzer nach einem Import ueberhaupt davon.
     */
    @Test
    fun `unplausible Anstiegswerte werden abgelehnt`() {
        assertNotNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_sekunden", 3600))
        assertNotNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_sekunden", 0))
        assertNotNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_start_prozent", 0))
        assertNotNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_start_prozent", 101))
    }

    @Test
    fun `plausible Anstiegswerte kommen durch`() {
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_sekunden", 30))
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_start_prozent", 15))
        // Beide Raender sind gueltig: 5 s ist der kuerzeste sinnvolle Anlauf, 100 % heisst
        // "kein Anstieg" - wirkungslos, aber harmlos.
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_sekunden", 5))
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_sekunden", 120))
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_start_prozent", 1))
        assertNull(ConfigBackupFilter.rangeRejection("weckton_anstieg_start_prozent", 100))
    }
}
