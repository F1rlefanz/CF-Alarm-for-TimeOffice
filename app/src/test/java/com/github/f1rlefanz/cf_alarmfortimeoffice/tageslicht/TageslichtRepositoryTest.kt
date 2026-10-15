package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

/**
 * Der Speicher fuer den Tageslicht-Ort (#140) - vor allem das Entsperr-Gate VOR jedem Lesen.
 *
 * WARUM DAS GATE SO WICHTIG IST: Ein CE-DataStore-Read im gesperrten Prozess wirft nicht, er
 * liefert still "leer" - und DataStore haelt dieses Leer fuer den GANZEN Store im Prozess. Liest
 * der Dimmer-Tick oder der AlarmReceiver im Direct Boot den Ort, liest danach auch
 * `AlarmRepository` "kein Bestand gespeichert" (Skill cfalarm-persistenz-und-auth, gemessen am
 * 01.10.2026). Der erste Test haelt deshalb fest, dass im gesperrten Zustand KEIN Lesen stattfindet.
 */
class TageslichtRepositoryTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val keyLand = stringPreferencesKey("tageslicht_land")
    private val keyPlz = stringPreferencesKey("tageslicht_plz")

    private val tabelleDe = """
        # Quelle: GeoNames - CC BY 4.0
        80	48.16	11.55	München
        803	48.14	11.56	München
    """.trimIndent()

    // ------------------------------------------------------------------ Fakes

    /** Zaehlt jedes Sammeln von [data]; kann auf Wunsch beim Lesen werfen. */
    private class SpionDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val zustand = MutableStateFlow(initial)
        var leseZugriffe = 0
        var wirftBeimLesen: Exception? = null

        override val data: Flow<Preferences> = flow {
            leseZugriffe++
            wirftBeimLesen?.let { throw it }
            zustand.collect { emit(it) }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val neu = transform(zustand.value)
            zustand.value = neu
            return neu
        }
    }

    private class FakeSpeicher(var wert: GespeicherterStandort? = null) : StandortSpeicher {
        var leseZugriffe = 0
        var wirftBeimLesen = false
        var commitGelingt = true

        override fun lies(): GespeicherterStandort? {
            leseZugriffe++
            if (wirftBeimLesen) throw ClassCastException("kaputter Typ")
            return wert
        }

        override fun schreibe(standort: GespeicherterStandort): Boolean {
            if (commitGelingt) wert = standort
            return commitGelingt
        }

        override fun loesche(): Boolean {
            if (commitGelingt) wert = null
            return commitGelingt
        }
    }

    private var tabellenLesungen = 0

    private fun repo(
        store: DataStore<Preferences> = SpionDataStore(),
        speicher: StandortSpeicher = FakeSpeicher(),
        entsperrt: () -> Boolean = { true },
        tabellen: (PlzLand) -> String? = { land -> tabellenLesungen++; if (land == PlzLand.DE) tabelleDe else null },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ) = TageslichtRepository(
        dataStore = store,
        istEntsperrt = entsperrt,
        standortSpeicher = speicher,
        tabellenText = tabellen,
        zone = { berlin },
        io = dispatcher
    )

    // ------------------------------------------------------------------ stand(): Gate

    @Test
    fun `gesperrter Nutzer - kein Ort und KEIN Lesezugriff auf DataStore, Prefs oder Assets`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val speicher = FakeSpeicher(GespeicherterStandort(481, 116, 1L))
        val r = repo(store, speicher, entsperrt = { false }, dispatcher = StandardTestDispatcher(testScheduler))

        val stand = r.stand()

        assertNull("gesperrt heisst keine Einschraenkung", stand.ort)
        assertEquals(berlin, stand.zone)
        assertEquals("DataStore darf im gesperrten Prozess NIE gelesen werden", 0, store.leseZugriffe)
        assertEquals("die Standort-Prefs liegen im CE-Storage", 0, speicher.leseZugriffe)
        assertEquals(0, tabellenLesungen)
    }

    @Test
    fun `gesperrter Nutzer - auch die Einstellungen fuer die UI lesen nichts`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val r = repo(store, entsperrt = { false }, dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(TageslichtEinstellungen.LEER, r.einstellungen.first())
        assertEquals(0, store.leseZugriffe)
    }

    // ------------------------------------------------------------------ stand(): Stufenkette

    @Test
    fun `gespeicherter Standort gewinnt`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val r = repo(store, FakeSpeicher(GespeicherterStandort(525, 134, 1L)), dispatcher = StandardTestDispatcher(testScheduler))

        val ort = r.stand().ort!!
        assertEquals(OrtQuelle.STANDORT, ort.quelle)
        assertEquals(52.5, ort.breite, 1e-9)
        assertEquals(13.4, ort.laenge, 1e-9)
    }

    @Test
    fun `Land und PLZ werden ueber die Asset-Tabelle aufgeloest`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        val ort = r.stand().ort!!
        assertEquals(OrtQuelle.PLZ, ort.quelle)
        assertEquals("München", ort.bezeichnung)
        assertEquals(48.14, ort.breite, 1e-9)
    }

    @Test
    fun `die PLZ-Tabelle wird nur einmal gelesen`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        r.stand()
        r.stand()
        r.plzTabelle(PlzLand.DE)
        assertEquals(1, tabellenLesungen)
    }

    @Test
    fun `fehlendes Asset - PLZ-Stufe entfaellt, die Zeitzone greift`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "AT", keyPlz to "1010"))
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        val ort = r.stand().ort!!
        assertEquals(OrtQuelle.ZEITZONE, ort.quelle)
    }

    @Test
    fun `unbekannter Laenderschluessel - keine PLZ-Stufe, kein Rateversuch`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "XX", keyPlz to "80331"))
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(OrtQuelle.ZEITZONE, r.stand().ort!!.quelle)
        assertEquals(0, tabellenLesungen)
    }

    @Test
    fun `nichts eingestellt - Zeitzonen-Ort`() = runTest {
        val r = repo(dispatcher = StandardTestDispatcher(testScheduler))
        val ort = r.stand().ort!!
        assertEquals(OrtQuelle.ZEITZONE, ort.quelle)
        assertEquals("Erfurt", ort.bezeichnung)
    }

    // ------------------------------------------------------------------ stand(): Fehler

    @Test
    fun `Lesefehler im DataStore - kein Ort statt Absturz`() = runTest {
        val store = SpionDataStore().apply { wirftBeimLesen = IOException("kaputt") }
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        assertNull(r.stand().ort)
    }

    @Test
    fun `Lesefehler in den Standort-Prefs - kein Ort statt Absturz`() = runTest {
        val speicher = FakeSpeicher().apply { wirftBeimLesen = true }
        val r = repo(speicher = speicher, dispatcher = StandardTestDispatcher(testScheduler))

        assertNull(r.stand().ort)
    }

    @Test
    fun `Lesefehler in der Asset-Tabelle - Zeitzone statt Absturz`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "DE", keyPlz to "80331"))
        val r = repo(store, tabellen = { throw IOException("Asset weg") }, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(OrtQuelle.ZEITZONE, r.stand().ort!!.quelle)
    }

    // ------------------------------------------------------------------ Schreiben

    @Test
    fun `speichereStandort rundet auf Zehntelgrad VOR dem Schreiben`() = runTest {
        val speicher = FakeSpeicher()
        val r = repo(speicher = speicher, dispatcher = StandardTestDispatcher(testScheduler))

        r.speichereStandort(48.137, 11.575, jetzt = 1234L)

        assertEquals(GespeicherterStandort(481, 116, 1234L), speicher.wert)
    }

    @Test
    fun `fehlgeschlagener commit wird gemeldet statt verschwiegen`() = runTest {
        val speicher = FakeSpeicher().apply { commitGelingt = false }
        val r = repo(speicher = speicher, dispatcher = StandardTestDispatcher(testScheduler))

        try {
            r.speichereStandort(48.1, 11.5, jetzt = 1L)
            fail("ein nicht gespeicherter Standort darf nicht als Erfolg gelten")
        } catch (e: IOException) {
            // erwartet
        }
        try {
            r.entferneStandort()
            fail("ein nicht entfernter Standort darf nicht als Erfolg gelten")
        } catch (e: IOException) {
            // erwartet
        }
    }

    @Test
    fun `unsinnige Koordinaten werden nicht gespeichert`() = runTest {
        val speicher = FakeSpeicher()
        val r = repo(speicher = speicher, dispatcher = StandardTestDispatcher(testScheduler))

        listOf(Double.NaN to 10.0, 91.0 to 10.0, 48.0 to 181.0).forEach { (b, l) ->
            try {
                r.speichereStandort(b, l, jetzt = 1L)
                fail("($b, $l) haette abgelehnt werden muessen")
            } catch (e: IllegalArgumentException) {
                // erwartet
            }
        }
        assertNull(speicher.wert)
    }

    @Test
    fun `entferneStandort loescht`() = runTest {
        val speicher = FakeSpeicher(GespeicherterStandort(481, 116, 1L))
        val r = repo(speicher = speicher, dispatcher = StandardTestDispatcher(testScheduler))

        r.entferneStandort()
        assertNull(speicher.wert)
    }

    @Test
    fun `setzePlz speichert getrimmt, leere PLZ loescht Land und PLZ`() = runTest {
        val store = SpionDataStore()
        val r = repo(store, dispatcher = StandardTestDispatcher(testScheduler))

        r.setzePlz(PlzLand.AT, " 1010 ")
        assertEquals("AT", store.zustand.value[keyLand])
        assertEquals("1010", store.zustand.value[keyPlz])

        r.setzePlz(PlzLand.AT, "  ")
        assertFalse(store.zustand.value.contains(keyLand))
        assertFalse(store.zustand.value.contains(keyPlz))
    }

    // ------------------------------------------------------------------ einstellungen

    @Test
    fun `einstellungen liefern Land, PLZ und Standort`() = runTest {
        val store = SpionDataStore(mutablePreferencesOf(keyLand to "CH", keyPlz to "8001"))
        val r = repo(store, FakeSpeicher(GespeicherterStandort(474, 85, 7L)), dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(
            TageslichtEinstellungen(GespeicherterStandort(474, 85, 7L), PlzLand.CH, "8001"),
            r.einstellungen.first()
        )
    }

    @Test
    fun `einstellungen degradieren bei Lesefehler auf LEER - catch hinter map`() = runTest {
        val store = SpionDataStore().apply { wirftBeimLesen = IOException("kaputt") }
        val r = repo(store, dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(TageslichtEinstellungen.LEER, r.einstellungen.first())
    }

    @Test
    fun `einstellungen degradieren auch bei einem Fehler im Standort-Lesen`() = runTest {
        val r = repo(speicher = FakeSpeicher().apply { wirftBeimLesen = true }, dispatcher = UnconfinedTestDispatcher(testScheduler))

        assertEquals(TageslichtEinstellungen.LEER, r.einstellungen.first())
    }

    @Test
    fun `einstellungen melden einen neu uebernommenen Standort`() = runTest {
        val speicher = FakeSpeicher()
        val r = repo(speicher = speicher, dispatcher = UnconfinedTestDispatcher(testScheduler))

        val gesehen = mutableListOf<TageslichtEinstellungen>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { r.einstellungen.take(2).toList(gesehen) }
        r.speichereStandort(48.137, 11.575, jetzt = 5L)
        advanceUntilIdle()
        job.join()

        assertNull(gesehen[0].standort)
        assertEquals(GespeicherterStandort(481, 116, 5L), gesehen[1].standort)
    }

    @Test
    fun `einstellungen bleiben nach einem Lesefehler im Standort abonniert`() = runTest {
        // Ein Typkonflikt in der Standort-Datei darf den Flow nicht BEENDEN: sonst erreicht ein
        // spaeteres "Standort uebernehmen" (das die Datei korrekt neu schreibt) den offenen
        // Bildschirm nie, und er zeigt weiter "kein Ort"
        val speicher = FakeSpeicher().apply { wirftBeimLesen = true }
        val r = repo(speicher = speicher, dispatcher = UnconfinedTestDispatcher(testScheduler))

        val gesehen = mutableListOf<TageslichtEinstellungen>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { r.einstellungen.take(2).toList(gesehen) }
        assertEquals(listOf(TageslichtEinstellungen.LEER), gesehen)

        speicher.wirftBeimLesen = false
        r.speichereStandort(48.137, 11.575, jetzt = 5L)
        advanceUntilIdle()

        assertEquals(2, gesehen.size)
        assertEquals(GespeicherterStandort(481, 116, 5L), gesehen[1].standort)
        job.join()
    }

    // ------------------------------------------------------------------ Prefs-Werte

    @Test
    fun `gespeicherte Werte ausserhalb des Wertebereichs gelten als kein Standort`() {
        assertEquals(GespeicherterStandort(481, 116, 1L), TageslichtStandortPrefs.ausWerten(481, 116, 1L))
        assertNull(TageslichtStandortPrefs.ausWerten(null, 116, 1L))
        assertNull(TageslichtStandortPrefs.ausWerten(481, null, 1L))
        assertNull(TageslichtStandortPrefs.ausWerten(901, 116, 1L))
        assertNull(TageslichtStandortPrefs.ausWerten(481, -1801, 1L))
        assertTrue(TageslichtStandortPrefs.ausWerten(-900, 1800, null) != null)
    }
}
