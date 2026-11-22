package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackupUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalTime

/**
 * #216: die Uebersetzung aus #214 laeuft auf dem Geraet einmalig, idempotent und nur ueber einen
 * lesbaren Bestand - und scheitert sie, bleibt alles unangetastet und [TypModellMigration.stand]
 * sagt es (Karte im System-Status). Durch die ECHTEN Stores (In-Memory-DataStore), nicht durch Ersatz.
 */
class TypModellMigrationTest {

    private val KEY_RULES = stringPreferencesKey("dim_rules")
    private val KEY_DIM_ON = booleanPreferencesKey("dim_enabled")
    private val KEY_DIMMER_MARKER = intPreferencesKey(DimmerModellMigration.KEY_MARKER_NAME)
    private val KEY_FOLGT_DIMMER = booleanPreferencesKey("dnd_follow_dimmer_enabled")
    private val KEY_WAEHREND_DIENST = booleanPreferencesKey("dnd_during_shift_enabled")
    private val KEY_RUF_ALTLISTE = stringSetPreferencesKey(DndPrefs.LEGACY_ONCALL_KEY_NAME)

    /** In-Memory-DataStore; [beimLesen] laeuft bei JEDEM Sammeln von [data] vor dem Wert. */
    private class SpeicherStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val stand = MutableStateFlow(initial)
        var beimLesen: () -> Unit = {}
        override val data: Flow<Preferences> = flow {
            beimLesen()
            emit(stand.value)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(stand.value).also { stand.value = it }
    }

    /** Haelt die Konfiguration wie das Repository: gelesen wird, was zuletzt gespeichert wurde. */
    private class SchichtSpeicher(var konfig: ShiftConfig) : IShiftUseCase {
        val gespeichert = mutableListOf<ShiftConfig>()
        var lesefehler: Throwable? = null
        override val shiftConfig: Flow<ShiftConfig> get() = flow { emit(konfig) }
        override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> {
            gespeichert += config
            konfig = config
            return Result.success(Unit)
        }
        override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> =
            lesefehler?.let { Result.failure(it) } ?: Result.success(konfig)
        override suspend fun recognizeShiftsInEvents(events: List<CalendarEvent>): Result<List<ShiftMatch>> =
            Result.success(emptyList())
    }

    private val dienst = ShiftDefinition(id = "f", name = "Frueh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 30))
    private val eigeneKonfig = ShiftConfig(definitions = listOf(dienst))

    private val universal = DimRule(
        id = "u", name = "Taeglich", shiftPattern = DimRule.SHIFT_UNIVERSAL,
        windows = listOf(DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = 21 * 60, endAnchor = DimAnchor.ALARM)),
        strength = 61, warmth = 33
    )

    /** Ein Bestandsgeraet nach der Dimmer-Migration: eine Regel, Dimmen an, DND folgt dem Dimmer. */
    private suspend fun bestandsgeraet(): SpeicherStore {
        val store = SpeicherStore(
            mutablePreferencesOf().apply {
                this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND
                this[KEY_DIM_ON] = true
                this[KEY_FOLGT_DIMMER] = true
                this[KEY_WAEHREND_DIENST] = false
            }
        )
        DimRuleRepository(store).upsert(universal)
        return store
    }

    private val armierer = mock<ZeitkettenArmierer>()
    private val alarmRepository = mock<IAlarmRepository> { onBlocking { isPersistenceBlocked() } doReturn false }

    private fun kontext(entsperrt: Boolean = true): Context {
        val userManager = mock<UserManager>()
        whenever(userManager.isUserUnlocked).thenReturn(entsperrt)
        val context = mock<Context>()
        whenever(context.getSystemService(UserManager::class.java)).thenReturn(userManager)
        return context
    }

    private fun migration(
        store: DataStore<Preferences>,
        schicht: IShiftUseCase,
        entsperrt: Boolean = true,
        alarme: IAlarmRepository = alarmRepository
    ) = TypModellMigration(
        context = kontext(entsperrt),
        dataStore = store,
        dimRuleRepository = DimRuleRepository(store),
        dimRuleUseCase = DimRuleUseCase(DimRuleRepository(store)),
        dimOverlayPrefs = DimOverlayPrefs(store),
        dndPrefs = DndPrefs(store),
        shiftUseCase = schicht,
        alarmRepository = alarme,
        armierer = armierer
    )

    private fun erwartet(store: SpeicherStore, config: ShiftConfig, dimmenAn: Boolean = true, folgtDimmer: Boolean = true) =
        TypEinstellungenUebersetzung.uebersetze(
            config,
            TypEinstellungenUebersetzung.Altbestand(
                regeln = listOf(universal), dimmenAn = dimmenAn, dndSolangeGedimmt = folgtDimmer,
                dndWaehrendEintrag = false, dndAusnahmen = emptySet(), rufCutoffMinuten = DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN
            ),
            DimRuleUseCase(DimRuleRepository(store))
        )

    @Before
    fun zuruecksetzen() = TypModellMigration.standZuruecksetzen()

    // --- Erfolg ---------------------------------------------------------------------------------

    @Test
    fun `Erfolg - genau ein Schreibvorgang mit Stufe, Altbestand byte-gleich, beide Ketten armiert`() = runTest {
        val store = bestandsgeraet()
        val vorher = store.stand.value
        val rohVorher = vorher[KEY_RULES]
        val schicht = SchichtSpeicher(eigeneKonfig)

        assertTrue(migration(store, schicht).migriereEinmalig())

        assertEquals(listOf(erwartet(store, eigeneKonfig)), schicht.gespeichert)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, schicht.konfig.modellStufe)
        assertEquals("die Regel kommt am Typ an", universal.windows, schicht.konfig.definitions.single().dimmen.fenster.map { it.fenster })
        // Kopieren statt verschieben: der settings-Store ist danach Schluessel fuer Schluessel gleich,
        // der Regel-Rohtext Zeichen fuer Zeichen.
        assertEquals(vorher.asMap(), store.stand.value.asMap())
        assertEquals(rohVorher, store.stand.value[KEY_RULES])
        verify(armierer).armiere(any(), any(), any())
        assertEquals(TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)
    }

    @Test
    fun `zweiter Lauf schreibt nicht - auch nicht, wenn der Altbestand inzwischen unlesbar ist`() = runTest {
        val store = bestandsgeraet()
        val schicht = SchichtSpeicher(eigeneKonfig)
        assertTrue(migration(store, schicht).migriereEinmalig())

        assertFalse(migration(store, schicht).migriereEinmalig())
        store.stand.value = store.stand.value.toMutablePreferences().apply { this[KEY_RULES] = "{kaputt" }
        assertFalse(migration(store, schicht).migriereEinmalig())

        assertEquals(1, schicht.gespeichert.size)
        verify(armierer, times(1)).armiere(any(), any(), any())
        assertEquals("ein migriertes Geraet meldet nie einen Fehler", TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)
    }

    /**
     * Festlegung #216: nie ueber eine leere oder die Standard-Konfiguration. `getCurrentShiftConfig()`
     * liefert die Standard-Konfiguration auch, wenn `shift_prefs` vor der Entsperrung gelesen wurde -
     * darueber geschrieben waere die echte Konfiguration weg. Die Karte nur, wenn etwas liegen bleibt.
     */
    @Test
    fun `Standard- oder leere Konfiguration - nichts geschrieben, Karte nur wenn der Altbestand etwas traegt`() = runTest {
        val mitRegel = bestandsgeraet()
        for (konfig in listOf(ShiftConfig.getDefaultConfig(), ShiftConfig(definitions = emptyList()))) {
            TypModellMigration.standZuruecksetzen()
            val schicht = SchichtSpeicher(konfig)
            assertFalse(migration(mitRegel, schicht).migriereEinmalig())
            assertTrue(schicht.gespeichert.isEmpty())
            assertFehler(TypModellMigration.Grund.KEINE_EIGENEN_SCHICHTTYPEN)
        }

        TypModellMigration.standZuruecksetzen()
        val ohneAltbestand = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        val frisch = SchichtSpeicher(ShiftConfig.getDefaultConfig())
        assertFalse(migration(ohneAltbestand, frisch).migriereEinmalig())
        assertTrue(frisch.gespeichert.isEmpty())
        assertEquals("frische Installation: keine Karte", TypModellMigration.Stand.Offen, TypModellMigration.stand.value)
    }

    // --- Die Tore: jedes schreibt nichts und setzt keine Stufe ------------------------------------

    @Test
    fun `gesperrter Storage schreibt nichts und zeigt keinen Fehler - erst nach dem Entsperren`() = runTest {
        val store = bestandsgeraet()
        val schicht = SchichtSpeicher(eigeneKonfig)

        assertFalse(migration(store, schicht, entsperrt = false).migriereEinmalig())
        assertTrue(schicht.gespeichert.isEmpty())
        assertEquals(TypModellMigration.Stand.Offen, TypModellMigration.stand.value)

        assertTrue(migration(store, schicht, entsperrt = true).migriereEinmalig())
    }

    @Test
    fun `unlesbare Konfiguration oder gesperrter Alarm-Bestand - nichts geschrieben, Grund sichtbar`() = runTest {
        val store = bestandsgeraet()
        val schicht = SchichtSpeicher(eigeneKonfig).apply { lesefehler = IllegalStateException("Broken") }
        assertFalse(migration(store, schicht).migriereEinmalig())
        assertFehler(TypModellMigration.Grund.KONFIGURATION_NICHT_LESBAR)

        schicht.lesefehler = null
        val gesperrt = mock<IAlarmRepository> { onBlocking { isPersistenceBlocked() } doReturn true }
        assertFalse(migration(store, schicht, alarme = gesperrt).migriereEinmalig())
        assertFehler(TypModellMigration.Grund.BESTAND_NICHT_LESBAR)

        assertTrue(schicht.gespeichert.isEmpty())
        verify(armierer, never()).armiere(any(), any(), any())
    }

    /**
     * Die Pflichtregel "Kein Fehler darf als leeres Erfolgsergebnis durchrutschen": der Anzeige-Weg
     * [DimRuleRepository.rules] liest denselben Rohtext als LEERE Liste. Darueber migriert, bekaeme
     * jeder Typ "keine Fenster", und die Stufe stuende - die Regeln kaemen nie mehr an.
     */
    @Test
    fun `Lesefehler der Regeln schreibt nichts und setzt keine Stufe`() = runTest {
        val store = bestandsgeraet()
        store.stand.value = store.stand.value.toMutablePreferences().apply { this[KEY_RULES] = "[{\"id\":\"u\",kaputt" }
        assertEquals("der Anzeige-Weg degradiert still", emptyList<DimRule>(), DimRuleRepository(store).getRules())
        val schicht = SchichtSpeicher(eigeneKonfig)

        assertFalse(migration(store, schicht).migriereEinmalig())

        assertTrue(schicht.gespeichert.isEmpty())
        assertEquals(0, schicht.konfig.modellStufe)
        assertFehler(TypModellMigration.Grund.ALTBESTAND_NICHT_LESBAR)
    }

    @Test
    fun `Reihenfolge - vor der Dimmer- und der Rufbereitschaft-Migration wird nichts geschrieben`() = runTest {
        val store = bestandsgeraet()
        val schicht = SchichtSpeicher(eigeneKonfig)

        store.stand.value = store.stand.value.toMutablePreferences().apply { remove(KEY_DIMMER_MARKER) }
        assertFalse(migration(store, schicht).migriereEinmalig())
        assertFehler(TypModellMigration.Grund.DIMMER_UMSTELLUNG_AUSSTEHEND)

        store.stand.value = store.stand.value.toMutablePreferences().apply {
            this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND
            this[KEY_RUF_ALTLISTE] = setOf("Frueh")
        }
        assertFalse(migration(store, schicht).migriereEinmalig())
        assertFehler(TypModellMigration.Grund.RUFBEREITSCHAFT_AUSSTEHEND)

        assertTrue(schicht.gespeichert.isEmpty())
    }

    /**
     * Die Kette aus der Festlegung: ein alter Bestand mit Wellness-Schluesseln (ohne `dim_enabled`)
     * geht ERST in Regeln (Dimmer-Migration) und DANN in Typen - das Wind-down kommt am Typ an.
     */
    @Test
    fun `Reihenfolge - Wellness-Altbestand geht ueber die Dimmer-Migration in die Typen`() = runTest {
        val store = SpeicherStore(
            mutablePreferencesOf().apply {
                this[booleanPreferencesKey("dim_wellness_enabled")] = true
                this[intPreferencesKey("dim_winddown_min")] = 90
            }
        )
        val schicht = SchichtSpeicher(eigeneKonfig)

        assertFalse("vor der Dimmer-Migration", migration(store, schicht).migriereEinmalig())
        assertTrue(DimmerModellMigration(kontext(), store, DimRuleUseCase(DimRuleRepository(store)), mock()).migriereEinmalig())
        assertTrue("danach", migration(store, schicht).migriereEinmalig())

        val dimmen = schicht.konfig.definitions.single().dimmen
        assertTrue(dimmen.an)
        val fenster = dimmen.fenster.single().fenster
        assertEquals(DimAnchor.ALARM, fenster.startAnchor)
        assertEquals(-90, fenster.startOffsetMinutes)
    }

    // --- Gleichzeitige Nutzeraenderung ----------------------------------------------------------

    /**
     * Was dieser Test belegt und was nicht: eine Aenderung, die WAEHREND des Lesens des Altbestands
     * gespeichert wird, bleibt erhalten, weil erst danach die Fassung gelesen wird, die geschrieben
     * wird. Das kurze Stueck zwischen diesem letzten Lesen und dem Schreiben deckt er nicht ab -
     * `saveShiftConfig` hat keinen atomaren Lese-Schreib-Weg (Grenze im Klassen-KDoc).
     */
    @Test
    fun `Nutzeraenderung waehrend des Lesens des Altbestands geht nicht verloren`() = runTest {
        val store = bestandsgeraet()
        val schicht = SchichtSpeicher(eigeneKonfig)
        val geaendert = eigeneKonfig.copy(definitions = listOf(dienst.copy(alarmTime = LocalTime.of(4, 44))))
        var einmal = true
        store.beimLesen = {
            if (einmal) {
                einmal = false
                schicht.konfig = geaendert // der Nutzer speichert, waehrend die Migration liest
            }
        }

        assertTrue(migration(store, schicht).migriereEinmalig())

        assertEquals(LocalTime.of(4, 44), schicht.konfig.definitions.single().alarmTime)
        assertEquals(erwartet(store, geaendert), schicht.konfig)
    }

    // --- Import --------------------------------------------------------------------------------

    /**
     * Ein alter Export (die nachgebaute Vorlage, v1.56.1, ohne Modell-Stufe) auf einem schon
     * migrierten Geraet: der ECHTE Import schreibt Konfiguration und Einstellungen, und danach
     * stehen Regeln und Schalter der DATEI an den Typen der DATEI.
     */
    @Test
    fun `Import eines alten Exports loest die Migration aus`() = runTest {
        val datei = checkNotNull(javaClass.classLoader?.getResource(VORLAGE)) { "$VORLAGE fehlt" }.readText()
        val store = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        val schicht = SchichtSpeicher(eigeneKonfig.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE))
        val import = ConfigBackupUseCase(
            mainDataStore = store,
            hueDataStore = SpeicherStore(),
            shiftUseCase = schicht,
            armierer = armierer,
            dimmerModellMigration = DimmerModellMigration(kontext(), store, DimRuleUseCase(DimRuleRepository(store)), mock()),
            rufbereitschaftMigration = RufbereitschaftMigration(kontext(), DndPrefs(store), schicht),
            typModellMigration = migration(store, schicht),
            hueLightUseCase = mock(),
            hueRuleUseCase = mock(),
            hueSmartScheduler = { mock<HueSmartScheduler>() }
        )

        assertTrue(import.import(datei).isSuccess)

        assertEquals("Import und Migration, je ein Schreibvorgang", 2, schicht.gespeichert.size)
        val ausDatei = schicht.gespeichert.first()
        assertEquals("der Export traegt keine Stufe", 0, ausDatei.modellStufe)
        val dnd = DndPrefs(store).altbestandOderFehler().getOrThrow()
        val nachher = TypEinstellungenUebersetzung.uebersetze(
            ausDatei,
            TypEinstellungenUebersetzung.Altbestand(
                regeln = DimRuleRepository(store).regelnOderFehler().getOrThrow(),
                dimmenAn = DimOverlayPrefs(store).dimmenAnOderFehler().getOrThrow(),
                dndSolangeGedimmt = dnd.folgtDimmer, dndWaehrendEintrag = dnd.waehrendDienst,
                dndAusnahmen = dnd.dienstAusnahmen, rufCutoffMinuten = dnd.rufCutoffMinuten
            ),
            DimRuleUseCase(DimRuleRepository(store))
        )
        assertEquals(nachher, schicht.konfig)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, schicht.konfig.modellStufe)
        assertTrue("die Regeln der Datei kommen an", schicht.konfig.definitions.any { it.dimmen.fenster.isNotEmpty() })
        assertEquals("die Vorlage hat den Cutoff 05:30", 330, dnd.rufCutoffMinuten)
    }

    private fun assertFehler(grund: TypModellMigration.Grund) =
        assertEquals(TypModellMigration.Stand.Fehlgeschlagen(grund), TypModellMigration.stand.value)

    private companion object {
        const val VORLAGE = "umbau/vorlage-nachgebaut-2026-10-16.json"
    }
}
