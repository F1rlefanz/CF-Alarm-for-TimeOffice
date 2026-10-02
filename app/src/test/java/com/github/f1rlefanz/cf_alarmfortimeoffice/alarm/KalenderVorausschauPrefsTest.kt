package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * #51: Die Kalender-Vorausschau ist eine Nutzereinstellung (7..90 Tage, Standard 14).
 *
 * Festgehalten wird vor allem die DEGRADATIONSRICHTUNG: ein nicht lesbarer Wert wird zum Ersatzwert
 * 14 - und dieser Ersatz wird NIE geschrieben. "Stille Degradierung darf nie zur Schreibwahrheit
 * werden" (CLAUDE.md, Persistenz): sonst ersetzte die Notlage-14 nach einem voruebergehenden
 * Lesefehler die gespeicherte Nutzerentscheidung.
 */
class KalenderVorausschauPrefsTest {

    /** Store, dessen Lesen wirft - und der zaehlt, ob jemand trotzdem zu schreiben versucht. */
    private class FailingDataStore : DataStore<Preferences> {
        var schreibversuche = 0
        override val data: Flow<Preferences> = flow { throw IOException("simulierter Lesefehler") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            schreibversuche++
            throw IOException("simulierter Schreibfehler")
        }
    }

    private class FakeDataStore(initial: Preferences = mutablePreferencesOf()) : DataStore<Preferences> {
        private val zustand = MutableStateFlow(initial)
        var schreibvorgaenge = 0
        override val data: Flow<Preferences> = zustand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            schreibvorgaenge++
            val neu = transform(zustand.value)
            zustand.value = neu
            return neu
        }
    }

    private val key = intPreferencesKey(KalenderVorausschauPrefs.KEY_TAGE_NAME)

    @Test
    fun `ohne gespeicherten Wert gilt der Standard 14`() = runTest {
        assertEquals(14, KalenderVorausschauPrefs(FakeDataStore()).tageNow())
    }

    @Test
    fun `Lesefehler ergibt den Ersatzwert 14 und schreibt ihn NICHT zurueck`() = runTest {
        val store = FailingDataStore()
        val prefs = KalenderVorausschauPrefs(store)

        assertEquals(14, prefs.tageNow())
        assertEquals("Auch der Flow wirft nicht", 14, prefs.tage.first())
        assertEquals("Der Ersatzwert darf nie zur Schreibwahrheit werden", 0, store.schreibversuche)
    }

    @Test
    fun `ein gespeicherter Wert wird durchgereicht`() = runTest {
        val prefs = KalenderVorausschauPrefs(FakeDataStore(mutablePreferencesOf().apply { this[key] = 56 }))
        assertEquals(56, prefs.tageNow())
    }

    @Test
    fun `ein unplausibler gespeicherter Wert wird zum Ersatzwert, nicht geklemmt`() = runTest {
        // Aus einem Backup einer kuenftigen Version oder einer von Hand bearbeiteten Datei.
        val store = FakeDataStore(mutablePreferencesOf().apply { this[key] = 400 })
        val prefs = KalenderVorausschauPrefs(store)

        assertEquals(14, prefs.tageNow())
        assertEquals("Lesen schreibt nichts", 0, store.schreibvorgaenge)
    }

    @Test
    fun `ein Wert falschen Typs wird zum Ersatzwert`() = runTest {
        val falscherTyp = longPreferencesKey(KalenderVorausschauPrefs.KEY_TAGE_NAME)
        val prefs = KalenderVorausschauPrefs(FakeDataStore(mutablePreferencesOf().apply { this[falscherTyp] = 28L }))

        assertEquals(14, prefs.tageNow())
    }

    @Test
    fun `setTage speichert gueltige Werte und lehnt ungueltige ab, ohne zu schreiben`() = runTest {
        val store = FakeDataStore()
        val prefs = KalenderVorausschauPrefs(store)

        assertTrue(prefs.setTage(28).isSuccess)
        assertEquals(28, prefs.tageNow())

        assertTrue(prefs.setTage(6).isFailure)
        assertTrue(prefs.setTage(91).isFailure)
        assertEquals("Abgelehnte Werte werden nicht geschrieben", 1, store.schreibvorgaenge)
        assertEquals(28, prefs.tageNow())
    }

    @Test
    fun `die Grenzen sind 7 und 90 Tage`() {
        assertFalse(KalenderVorausschauPrefs.istGueltig(6))
        assertTrue(KalenderVorausschauPrefs.istGueltig(7))
        assertTrue(KalenderVorausschauPrefs.istGueltig(90))
        assertFalse(KalenderVorausschauPrefs.istGueltig(91))
        assertTrue(
            "Jede Schnellwahl muss selbst gueltig sein",
            KalenderVorausschauPrefs.SCHNELLWAHL_TAGE.all { KalenderVorausschauPrefs.istGueltig(it) }
        )
        assertTrue(
            "Der Standard muss in der Schnellwahl stehen, sonst ist er nicht ablesbar markiert",
            KalenderVorausschauPrefs.STANDARD_TAGE in KalenderVorausschauPrefs.SCHNELLWAHL_TAGE
        )
    }

    @Test
    fun `die Eingabepruefung lehnt mit Text ab statt zu klemmen`() {
        fun urteil(text: String) = KalenderVorausschauPrefs.pruefeEingabe(text)

        assertEquals(KalenderVorausschauPrefs.EingabeUrteil.Gueltig(28), urteil("28"))
        assertEquals(KalenderVorausschauPrefs.EingabeUrteil.Gueltig(7), urteil(" 7 "))
        assertEquals(KalenderVorausschauPrefs.EingabeUrteil.Gueltig(90), urteil("90"))

        listOf("", "  ", "abc", "2.5", "6", "0", "-3", "91", "120").forEach { eingabe ->
            val ergebnis = urteil(eingabe)
            assertTrue(
                "'$eingabe' muss abgelehnt werden",
                ergebnis is KalenderVorausschauPrefs.EingabeUrteil.Abgelehnt
            )
            assertTrue(
                "Die Ablehnung braucht einen Text",
                (ergebnis as KalenderVorausschauPrefs.EingabeUrteil.Abgelehnt).grund.isNotBlank()
            )
        }
        assertTrue(
            "Zu gross nennt die Obergrenze",
            (urteil("120") as KalenderVorausschauPrefs.EingabeUrteil.Abgelehnt).grund.contains("90")
        )
        assertTrue(
            "Zu klein nennt die Untergrenze",
            (urteil("3") as KalenderVorausschauPrefs.EingabeUrteil.Abgelehnt).grund.contains("7")
        )
    }
}
