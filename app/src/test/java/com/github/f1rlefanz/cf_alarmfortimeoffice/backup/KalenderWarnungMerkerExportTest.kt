package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das Entprellungs-Gedaechtnis der Kalender-Warnung darf die Geraetegrenze nicht ueberschreiten.
 *
 * ## Der Befund
 *
 * `CalendarUnavailablePrefs` haelt drei Schluessel. Einer davon ist eine echte Einstellung
 * (`calendar_unavailable_notification_enabled` - "will ich diese Meldung ueberhaupt?"), die
 * anderen beiden sind das Gedaechtnis der Entprellung: welche Kalender beim VORIGEN Lauf
 * gescheitert sind und ueber welche schon gemeldet wurde. Bis v1.40.3 galten alle drei als
 * exportierbar.
 *
 * ## Warum das die gefaehrliche Richtung ist
 *
 * `entscheideBenachrichtigung()` rechnet `neuZuMelden = beharrlich - bereitsGemeldet`. Ein
 * importiertes `calendar_unavailable_notified` haelt das neue Geraet also fuer Kalender-IDs
 * zustaendig, ueber die es nie etwas gesagt hat - und schweigt dann ueber genau den Zustand, der
 * die Wecker langsam versiegen laesst. Dass die Kalenderauswahl selbst nicht exportiert wird,
 * hilft nicht: bei gleichem Google-Konto sind es dieselben IDs. `CalendarUnavailablePrefs`
 * benennt diese Richtung selbst als die verbotene ("gilt als bereits gemeldet" hiesse, die
 * einzige Warnung ueber versiegende Wecker faellt aus, und zwar dauerhaft und lautlos).
 *
 * `calendar_unavailable_last_failed` ist die harmlose Richtung - die erste Stoerung auf dem neuen
 * Geraet zaehlte als zweite, es wuerde also FRUEHER gewarnt. Trotzdem ist es eine Beobachtung
 * DIESES Geraets und keine Einstellung; ein halb mitgenommenes Gedaechtnis waere die
 * unuebersichtlichere Lage.
 *
 * ## Warum als Rundtrip und nicht nur als Filter-Frage
 *
 * `ConfigBackupUseCase.export`/`import` brauchen einen echten DataStore und den halben
 * Hilt-Graphen; die Entscheidung faellt aber vollstaendig in vier reinen Funktionen
 * ([ConfigBackupFilter.isExportable], [ConfigBackupUseCase.toStoredValue],
 * [ConfigBackupFilter.exclusionReason], [ConfigBackupUseCase.applyValue]). Der Rundtrip unten
 * ruft genau diese - er zeigt den Weg von Geraet A nach Geraet B mit den echten Bausteinen,
 * statt nur eine Mengenzugehoerigkeit zu behaupten.
 *
 * ## Der Export ist nur EINER der beiden Wege
 *
 * Googles Auto-Backup und der Geraetetransfer sichern den kompletten `settings`-Store als Datei
 * und sehen diesen Filter deshalb nie. Dagegen steht `DeviceLocalFlagsGuard` - siehe
 * [KalenderWarnungMerkerBackupTest].
 */
class KalenderWarnungMerkerExportTest {

    private val gemeldet = stringSetPreferencesKey("calendar_unavailable_notified")
    private val zuletztGescheitert = stringSetPreferencesKey("calendar_unavailable_last_failed")
    private val meldungAn = booleanPreferencesKey("calendar_unavailable_notification_enabled")

    /**
     * Geraet A hat einen dauerhaft unerreichbaren Kalender gemeldet. Was davon darf mit?
     * Nur die Antwort auf "will ich diese Meldung?" - nicht die Beobachtung, dass sie schon
     * ausgesprochen wurde.
     */
    @Test
    fun `das Gedaechtnis der Entprellung bleibt auf dem Geraet, der Schalter reist mit`() {
        val geraetA = mutablePreferencesOf(
            gemeldet to setOf("dienstplan@example.com"),
            zuletztGescheitert to setOf("dienstplan@example.com"),
            meldungAn to true
        )

        val datei = exportiere(geraetA)

        assertFalse(
            "Der 'bereits gemeldet'-Merker beschreibt eine Beobachtung DIESES Geraets - " +
                "importiert schaltet er die einzige Warnung ueber versiegende Wecker stumm",
            datei.containsKey("calendar_unavailable_notified")
        )
        assertFalse(
            "Auch der Beharrlichkeits-Merker ist eine Beobachtung dieses Geraets",
            datei.containsKey("calendar_unavailable_last_failed")
        )
        assertTrue(
            "Der Schalter ist eine echte Einstellung und soll auf ein neues Geraet mitkommen",
            datei.containsKey("calendar_unavailable_notification_enabled")
        )
    }

    /**
     * Der Filter gilt in BEIDE Richtungen: eine von Hand zusammengebaute oder aus einer aelteren
     * Version stammende Datei darf den Merker genauso wenig einschleusen - und die Ablehnung wird
     * BENANNT, nicht verschwiegen.
     */
    @Test
    fun `eine alte Datei kann den Merker nicht einschleusen`() {
        val alteDatei = mapOf(
            "calendar_unavailable_notified" to
                StoredValue("stringSet", setValue = listOf("dienstplan@example.com")),
            "calendar_unavailable_last_failed" to
                StoredValue("stringSet", setValue = listOf("dienstplan@example.com")),
            "calendar_unavailable_notification_enabled" to StoredValue("boolean", "true")
        )
        val abgelehnt = mutableListOf<String>()

        val geraetB = importiere(alteDatei, abgelehnt)

        assertNull("Kein fremdes 'bereits gemeldet' im Store", geraetB[gemeldet])
        assertNull("Kein fremdes 'zuletzt gescheitert' im Store", geraetB[zuletztGescheitert])
        assertEquals(true, geraetB[meldungAn])
        assertEquals(
            listOf(
                "calendar_unavailable_last_failed (Laufzeitzustand)",
                "calendar_unavailable_notified (Laufzeitzustand)"
            ),
            abgelehnt.sorted()
        )
    }

    /**
     * WOZU DAS GANZE - die Folge in einem Satz, ausgerechnet statt behauptet.
     *
     * Geraet B sieht denselben Kalender in zwei aufeinanderfolgenden Laeufen scheitern. Mit
     * importiertem Merker haelt es die Stoerung fuer bereits ausgesprochen und schweigt; mit
     * leerem Merker (so wie ein frisches Geraet ihn hat) warnt es.
     */
    @Test
    fun `mit importiertem Merker bliebe die Warnung aus`() {
        val gescheitert = setOf("dienstplan@example.com")

        val mitFremdemMerker = CalendarUnavailableNotifier.entscheideBenachrichtigung(
            jetztGescheitert = gescheitert,
            zuletztGescheitert = gescheitert,
            bereitsGemeldet = gescheitert
        )
        assertTrue(
            "Genau die Lage, die der Import erzeugt haette: dauerhaft und lautlos stumm",
            mitFremdemMerker.zuMelden.isEmpty()
        )

        val ohneFremdenMerker = CalendarUnavailableNotifier.entscheideBenachrichtigung(
            jetztGescheitert = gescheitert,
            zuletztGescheitert = gescheitert,
            bereitsGemeldet = emptySet()
        )
        assertEquals(gescheitert, ohneFremdenMerker.zuMelden)
    }

    // ------------------------------------------------------------------
    // Die beiden Wege, mit den echten Bausteinen aus ConfigBackupUseCase
    // ------------------------------------------------------------------

    /** Wie `ConfigBackupUseCase.exportableValues`: filtern, dann typerhaltend umwandeln. */
    private fun exportiere(prefs: Preferences): Map<String, StoredValue> =
        prefs.asMap()
            .asSequence()
            .filter { (key, _) -> ConfigBackupFilter.isExportable(key.name) }
            .mapNotNull { (key, value) ->
                ConfigBackupUseCase.toStoredValue(value)?.let { key.name to it }
            }
            .toMap()

    /** Wie `ConfigBackupUseCase.writeValues`: erneut filtern - und Abgelehntes benennen. */
    private fun importiere(
        datei: Map<String, StoredValue>,
        abgelehnt: MutableList<String>
    ): MutablePreferences {
        val ziel = mutablePreferencesOf()
        datei.forEach { (name, stored) ->
            val grund = ConfigBackupFilter.exclusionReason(name)
            if (grund != null) {
                abgelehnt += "$name ($grund)"
                return@forEach
            }
            ConfigBackupUseCase.applyValue(ziel, name, stored)
        }
        return ziel
    }
}
