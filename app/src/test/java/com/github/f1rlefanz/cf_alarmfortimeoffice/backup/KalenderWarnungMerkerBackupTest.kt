package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalFlagsGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DER ZWEITE WEG, auf dem das Gedaechtnis der Kalender-Warnung die Geraetegrenze ueberschreitet -
 * und der, den ein Filter per Konstruktion nie sieht.
 *
 * ## Der Befund (Issue #84)
 *
 * [KalenderWarnungMerkerExportTest] deckt den Konfigurations-Export ab: seit v1.40.4 nimmt
 * [ConfigBackupFilter] `calendar_unavailable_notified` und `calendar_unavailable_last_failed` aus
 * der Datei, in beide Richtungen. Zum selben Schaden fuehrt aber Googles Auto-Backup bzw. der
 * Geraetetransfer: `backup_rules.xml` und `data_extraction_rules.xml` sichern
 * `<include domain="file" path="datastore"/>`, also den kompletten `settings`-Store - und dort
 * liegen alle drei `calendar_unavailable_*`-Schluessel. Ein DataStore-Preferences-Store ist EINE
 * Datei; einzelne Schluessel lassen sich nicht ausnehmen. Genau dafuer gibt es
 * [DeviceLocalFlagsGuard].
 *
 * ## Warum es sich nicht von selbst repariert
 *
 * Die Backup-Regeln ordnen die Laufzeit-Spiegel im selben Store ausdruecklich als harmlos ein:
 * "sie werden beim naechsten syncAlarms()/applyCurrentState() neu abgeleitet". Fuer diesen Merker
 * gilt das NICHT. `entscheideBenachrichtigung()` rechnet
 * `neuZuMelden = beharrlich - bereitsGemeldet`:
 *  - Scheitert der mitgewanderte Kalender auf dem neuen Geraet NICHT, faellt seine ID beim ersten
 *    Wartungslauf per `intersect` heraus - selbstheilend, kein Problem.
 *  - Scheitert er DOCH (der Fall, um den es geht), ist `neuZuMelden` bei jedem Lauf leer, es wird
 *    nichts gemeldet, und derselbe `intersect` haelt die ID im Merker fest. Dauerhaft und lautlos,
 *    solange die Stoerung anhaelt.
 *
 * Und die Stoerung ist gerade die, die die Wecker versiegen laesst: die Vollstaendigkeits-Sperren
 * verhindern zu Recht das Loeschen von Weckern - damit aber auch jedes Anlegen. Dass die
 * Kalenderauswahl selbst nicht mitkommt, entschaerft nichts: bei gleichem Google-Konto sind es
 * dieselben Kalender-IDs.
 *
 * ## Warum mit den echten Bausteinen und nicht am Geraet
 *
 * Ein Nachweis am Geraet braeuchte einen echten Restore auf ein zweites Geraet mit demselben
 * Google-Konto und einen ueber zwei Wartungslaeufe unerreichbaren Kalender. Die Entscheidung
 * faellt aber vollstaendig in zwei reine Funktionen ([DeviceLocalFlagsGuard.shouldResetFlags] und
 * [DeviceLocalFlagsGuard.isDeviceLocalKey]); der Ablauf unten ruft genau diese und bildet nur die
 * vier Zeilen nach, die in `resetIfDeviceChanged()` daraus das Loeschen machen.
 */
class KalenderWarnungMerkerBackupTest {

    private val geraeteMarker = stringPreferencesKey("device_local_flags_marker")
    private val gemeldet = stringSetPreferencesKey("calendar_unavailable_notified")
    private val zuletztGescheitert = stringSetPreferencesKey("calendar_unavailable_last_failed")
    private val meldungAn = booleanPreferencesKey("calendar_unavailable_notification_enabled")
    private val schlummerdauer = intPreferencesKey("snooze_minutes")

    private val altesGeraet = "Fairphone/FP6/FP6:16/…"
    private val neuesGeraet = "google/sdk_gphone64_x86_64/…"
    private val dienstplan = "dienstplan@example.com"

    /**
     * Der Restore: Geraet B startet mit dem kompletten `settings`-Store von Geraet A. Was der
     * Waechter davon anfassen darf, ist genau das Gedaechtnis - nicht der Schalter und nicht die
     * Nutzereinstellungen daneben.
     */
    @Test
    fun `nach einem Restore raeumt der Waechter das Gedaechtnis, nicht die Einstellungen`() {
        val geraetB = restoreVonGeraetA()

        assertTrue(
            "Der mitgesicherte Marker stammt von Geraet A - das MUSS als Geraetewechsel gelten",
            DeviceLocalFlagsGuard.shouldResetFlags(geraetB[geraeteMarker], neuesGeraet)
        )

        val entfernt = waechterLaeuft(geraetB)

        assertEquals(
            setOf("calendar_unavailable_notified", "calendar_unavailable_last_failed"),
            entfernt
        )
        assertFalse("Der 'schon gewarnt'-Merker darf nicht mitreisen", geraetB.contains(gemeldet))
        assertFalse(
            "Auch der Beharrlichkeits-Merker gehoert zum Geraet - ganz oder gar nicht",
            geraetB.contains(zuletztGescheitert)
        )
        assertEquals(
            "Der Schalter ist eine echte Einstellung - eine abgeschaltete Meldung darf nicht " +
                "ungefragt zurueckkommen",
            false,
            geraetB[meldungAn]
        )
        assertEquals("Nutzereinstellungen bleiben unberuehrt", 9, geraetB[schlummerdauer])
    }

    /**
     * WOZU DAS GANZE - die Folge ausgerechnet statt behauptet, mit dem Ablauf aus der Praxis:
     * derselbe Kalender scheitert auf dem neuen Geraet in mehreren aufeinanderfolgenden
     * Wartungslaeufen.
     *
     * Mit mitgewandertem Merker bleibt die Warnung in JEDEM Lauf aus, und der Merker haelt sich
     * dabei selbst am Leben - die Lage aendert sich also auch beim dritten, zehnten, hundertsten
     * Lauf nicht. Mit geraeumtem Merker wird beim zweiten Lauf gewarnt, so wie auf einem frisch
     * eingerichteten Geraet.
     */
    @Test
    fun `ohne den Waechter bliebe die Warnung auf dem neuen Geraet dauerhaft aus`() {
        val gescheitert = setOf(dienstplan)

        // Ohne Waechter: beide Mengen kommen aus dem Backup mit.
        var merker = gescheitert
        var letzterLauf = gescheitert
        repeat(3) { lauf ->
            val e = CalendarUnavailableNotifier.entscheideBenachrichtigung(
                jetztGescheitert = gescheitert,
                zuletztGescheitert = letzterLauf,
                bereitsGemeldet = merker
            )
            assertTrue(
                "Lauf $lauf schweigt - genau der stumme Zustand, gegen den die Meldung gebaut wurde",
                e.zuMelden.isEmpty()
            )
            assertEquals("Der Merker haelt sich selbst am Leben", gescheitert, e.neuerBereitsGemeldet)
            merker = e.neuerBereitsGemeldet
            letzterLauf = e.neuerZuletztGescheitert
        }

        // Mit Waechter: beide Mengen sind geraeumt, das neue Geraet faengt bei null an.
        val ersterLauf = CalendarUnavailableNotifier.entscheideBenachrichtigung(
            jetztGescheitert = gescheitert,
            zuletztGescheitert = emptySet(),
            bereitsGemeldet = emptySet()
        )
        assertTrue(
            "Ein einzelner Aussetzer wird weiterhin nicht gemeldet - die Entprellung bleibt",
            ersterLauf.zuMelden.isEmpty()
        )

        val zweiterLauf = CalendarUnavailableNotifier.entscheideBenachrichtigung(
            jetztGescheitert = gescheitert,
            zuletztGescheitert = ersterLauf.neuerZuletztGescheitert,
            bereitsGemeldet = ersterLauf.neuerBereitsGemeldet
        )
        assertEquals(
            "Beim zweiten Lauf in Folge wird gewarnt - genau wie auf einem frischen Geraet",
            gescheitert,
            zweiterLauf.zuMelden
        )
    }

    // ------------------------------------------------------------------
    // Die beiden Bausteine des Restore-Wegs
    // ------------------------------------------------------------------

    /**
     * Der `settings`-Store, wie er nach einem Auto-Backup/Geraetetransfer auf Geraet B ankommt:
     * vollstaendig, inklusive des Geraete-Markers von Geraet A. Der Schalter steht bewusst auf
     * `false` (der Nutzer hatte die Meldung abgeschaltet) - so wird sichtbar, dass der Waechter ihn
     * nicht auf den Default `true` zuruecksetzt.
     */
    private fun restoreVonGeraetA(): MutablePreferences = mutablePreferencesOf(
        geraeteMarker to altesGeraet,
        gemeldet to setOf(dienstplan),
        zuletztGescheitert to setOf(dienstplan),
        meldungAn to false,
        schlummerdauer to 9
    )

    /**
     * Die vier Zeilen aus `DeviceLocalFlagsGuard.resetIfDeviceChanged()`, die aus der Entscheidung
     * das Loeschen machen - die Entscheidung selbst ist [DeviceLocalFlagsGuard.isDeviceLocalKey]
     * und wird hier echt aufgerufen. Die Methode drumherum braucht einen `DataStore`; ihr Rumpf ist
     * genau das hier.
     *
     * @return die Namen der entfernten Schluessel
     */
    private fun waechterLaeuft(prefs: MutablePreferences): Set<String> {
        val entfernt = prefs.asMap().keys.filter { DeviceLocalFlagsGuard.isDeviceLocalKey(it.name) }
        entfernt.forEach { prefs.remove(it) }
        prefs[geraeteMarker] = neuesGeraet
        return entfernt.map { it.name }.toSet()
    }
}
