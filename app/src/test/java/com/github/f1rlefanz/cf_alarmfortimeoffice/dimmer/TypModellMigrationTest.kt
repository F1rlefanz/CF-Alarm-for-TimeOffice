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
import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackup
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.HueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypLicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.UmbauVorlagen
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalTime

/**
 * #216: die Uebersetzung aus #214 laeuft auf dem Geraet einmalig, idempotent und nur ueber einen
 * lesbaren Bestand - und scheitert sie, bleibt alles unangetastet und [TypModellMigration.stand]
 * sagt es (Karte im System-Status). Durch die ECHTEN Stores (In-Memory-DataStore), nicht durch Ersatz.
 *
 * #248: derselbe Einstieg zieht danach die Hue-Regeln an die Typen (Abschnitt „Licht“ unten). Die
 * Tests der Etappe 3 laufen auf einer Konfiguration, deren Licht schon migriert ist ([eigeneKonfig]),
 * damit sie weiter genau einen Schritt pruefen.
 */
class TypModellMigrationTest {

    private val KEY_RULES = stringPreferencesKey("dim_rules")
    private val KEY_DIM_ON = booleanPreferencesKey("dim_enabled")
    private val KEY_DIMMER_MARKER = intPreferencesKey(DimmerModellMigration.KEY_MARKER_NAME)
    private val KEY_FOLGT_DIMMER = booleanPreferencesKey("dnd_follow_dimmer_enabled")
    private val KEY_WAEHREND_DIENST = booleanPreferencesKey("dnd_during_shift_enabled")
    private val KEY_RUF_ALTLISTE = stringSetPreferencesKey(DndPrefs.LEGACY_ONCALL_KEY_NAME)
    private val KEY_HUE_RULES = stringPreferencesKey("hue_schedule_rules")

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
    // #248: `lichtStufe` gesetzt - der Licht-Schritt ist hier erledigt und schreibt nicht mit.
    private val eigeneKonfig = ShiftConfig(definitions = listOf(dienst), lichtStufe = TypLichtUebersetzung.LICHT_STUFE)

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
    // #249: geteilt ueber alle Instanzen eines Tests - die Licht-Tests bauen je Lauf eine neue.
    private val scheduler = mock<HueSmartScheduler>()
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
        alarme: IAlarmRepository = alarmRepository,
        hue: IHueConfigRepository = HueConfigRepository(SpeicherStore()),
        planer: dagger.Lazy<HueSmartScheduler> = dagger.Lazy { scheduler }
    ) = TypModellMigration(
        context = kontext(entsperrt),
        dataStore = store,
        dimRuleRepository = DimRuleRepository(store),
        dimRuleUseCase = DimRuleUseCase(DimRuleRepository(store)),
        dimOverlayPrefs = DimOverlayPrefs(store),
        dndPrefs = DndPrefs(store),
        shiftUseCase = schicht,
        alarmRepository = alarme,
        hueConfigRepository = hue,
        armierer = armierer,
        hueSmartScheduler = planer
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

    // --- Vor der Migration am Typ gespeichert (#234) ---------------------------------------------

    /**
     * #234 (Befund aus #221): frische Installation, Stufe 0, Standard-Typen. Auf der Typ-Seite wird
     * ein Fenster gespeichert (`config.copy`, die Stufe bleibt 0) - die Konfiguration weicht jetzt
     * vom Standard ab, Tor 7 greift nicht mehr. Der Altbestand ist leer (keine Regel, beide
     * DND-Schalter aus, `dim_enabled` fehlt und liest sich als aus): die Migration darf das Fenster
     * nicht durch den Altbestand ersetzen und keinem Typ ein `an = false` geben - nur die Stufe setzen.
     */
    @Test
    fun `frische Installation - am Typ gespeichertes Fenster bleibt, leerer Altbestand setzt nur die Stufe`() = runTest {
        val standard = ShiftConfig.getDefaultConfig()
        assertTrue("die Standard-Konfiguration hat Typen", standard.definitions.isNotEmpty())
        val fenster = TypDimmFenster(
            fenster = DimWindow(startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60, endAnchor = DimAnchor.ALARM),
            staerke = 40
        )
        val gespeichert = standard.copy(
            definitions = standard.definitions.mapIndexed { i, typ ->
                if (i == 0) typ.copy(dimmen = typ.dimmen.mitFenster(listOf(fenster))) else typ
            },
            lichtStufe = TypLichtUebersetzung.LICHT_STUFE // #248: nur der erste Schritt
        )
        assertEquals(0, gespeichert.modellStufe)
        val store = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        val schicht = SchichtSpeicher(gespeichert)

        assertTrue("Tor 7 greift nicht mehr - die Migration schreibt", migration(store, schicht).migriereEinmalig())

        val typ = schicht.konfig.definitions.first()
        assertEquals("das gespeicherte Fenster bleibt", listOf(fenster), typ.dimmen.fenster)
        assertTrue("an bleibt an", typ.dimmen.an)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, schicht.konfig.modellStufe)
        assertEquals(
            "ausser der Stufe aendert sich nichts, auch an den unberuehrten Typen",
            gespeichert.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE),
            schicht.konfig
        )
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

        // #248: dazu der Licht-Schritt - diese Vorlage hat keine Hue-Regeln, er setzt nur die Stufe.
        assertEquals("Import und beide Schritte, je ein Schreibvorgang", 3, schicht.gespeichert.size)
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
        assertEquals(nachher.copy(lichtStufe = TypLichtUebersetzung.LICHT_STUFE), schicht.konfig)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, schicht.konfig.modellStufe)
        assertTrue("die Regeln der Datei kommen an", schicht.konfig.definitions.any { it.dimmen.fenster.isNotEmpty() })
        assertEquals("die Vorlage hat den Cutoff 05:30", 330, dnd.rufCutoffMinuten)
    }

    // --- Licht (#248) ---------------------------------------------------------------------------

    private val hueJson = Json { encodeDefaults = true }

    private fun hueRegel(id: String, muster: String, enabled: Boolean = true) = HueScheduleRule(
        id = id, name = "Regel $id", shiftPattern = muster, enabled = enabled,
        timeRanges = listOf(
            HueTimeRange(
                listOf(HueLightAction(targetType = TargetType.LIGHT, targetId = "1", targetName = "Flur", actionType = ActionType.TURN_ON, on = true))
            )
        )
    )

    private fun hueStore(vararg regeln: HueScheduleRule) = SpeicherStore(
        mutablePreferencesOf().apply {
            this[KEY_HUE_RULES] = hueJson.encodeToString(ListSerializer(HueScheduleRule.serializer()), regeln.toList())
        }
    )

    /** Etappe 3 ist durch, das Licht noch nicht: der Stand eines Geraets beim Update auf Etappe 4. */
    private val vorDemLicht = eigeneKonfig.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE, lichtStufe = 0)

    @Test
    fun `Licht - Erfolg schreibt einmal, Hue-Altbestand byte-gleich, zweiter Lauf schreibt nichts`() = runTest {
        val regel = hueRegel("r1", "frueh") // Name in anderer Schreibweise als der Typ
        val hue = hueStore(regel)
        val vorher = hue.stand.value
        val schicht = SchichtSpeicher(vorDemLicht)

        assertTrue(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hue)).migriereEinmalig())

        assertEquals(listOf(TypLichtUebersetzung.uebersetze(vorDemLicht, listOf(regel)).config), schicht.gespeichert)
        assertEquals("die Regel steht am Typ", listOf(regel), schicht.konfig.definitions.single().licht.regeln)
        assertEquals(TypLichtUebersetzung.LICHT_STUFE, schicht.konfig.lichtStufe)
        assertEquals("kopieren statt verschieben", vorher.asMap(), hue.stand.value.asMap())
        verify(armierer, never()).armiere(any(), any(), any())
        assertEquals(TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)

        // Zweiter Lauf - auch, wenn der Hue-Store inzwischen unlesbar waere: nichts, kein Fehler.
        assertFalse(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hue)).migriereEinmalig())
        hue.beimLesen = { throw IOException("kaputt") }
        assertFalse(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hue)).migriereEinmalig())
        assertEquals(1, schicht.gespeichert.size)
        assertEquals(TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)
    }

    /**
     * Die Pflichtregel "Kein Fehler darf als leeres Erfolgsergebnis durchrutschen": ein unlesbarer
     * Hue-Store als "keine Regeln" gelesen setzte die Stufe endgueltig - die Regeln kaemen nie an.
     * Zwei Wege: undekodierbarer Inhalt und ein Lesefehler des Stores.
     */
    @Test
    fun `Licht - Lesefehler des Hue-Stores setzt keine Stufe und schreibt nichts, der Grund steht in stand`() = runTest {
        val undekodierbar = SpeicherStore(mutablePreferencesOf().apply { this[KEY_HUE_RULES] = "[{\"id\":\"r1\",kaputt" })
        val lesefehler = hueStore(hueRegel("r1", "Frueh")).apply { beimLesen = { throw IOException("kaputt") } }
        for (hue in listOf(undekodierbar, lesefehler)) {
            TypModellMigration.standZuruecksetzen()
            val vorher = hue.stand.value
            val schicht = SchichtSpeicher(vorDemLicht)

            assertFalse(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hue)).migriereEinmalig())

            assertTrue(schicht.gespeichert.isEmpty())
            assertEquals(0, schicht.konfig.lichtStufe)
            assertEquals(vorher.asMap(), hue.stand.value.asMap())
            assertLichtFehler(TypModellMigration.Grund.HUE_REGELN_NICHT_LESBAR)
        }
    }

    /**
     * Die Schritte haengen nicht aneinander: scheitert der eine, schreibt der andere trotzdem - und
     * die Karte nennt den gescheiterten. Ein gesperrter Alarm-Bestand haelt auch das Licht an.
     */
    @Test
    fun `Licht - die beiden Schritte sind unabhaengig, die Karte nennt den gescheiterten`() = runTest {
        val regel = hueRegel("r1", "Frueh")

        // Dimmer-Umstellung haengt: Etappe 3 schreibt nicht, das Licht schon.
        val ohneMarker = bestandsgeraet().apply { stand.value = stand.value.toMutablePreferences().apply { remove(KEY_DIMMER_MARKER) } }
        val schicht = SchichtSpeicher(eigeneKonfig.copy(lichtStufe = 0))
        assertTrue(migration(ohneMarker, schicht, hue = HueConfigRepository(hueStore(regel))).migriereEinmalig())
        assertEquals(1, schicht.gespeichert.size)
        assertEquals(0, schicht.konfig.modellStufe)
        assertEquals(listOf(regel), schicht.konfig.definitions.single().licht.regeln)
        assertFehler(TypModellMigration.Grund.DIMMER_UMSTELLUNG_AUSSTEHEND)

        // Hue unlesbar: Etappe 3 schreibt, das Licht nicht.
        TypModellMigration.standZuruecksetzen()
        val kaputt = hueStore(regel).apply { beimLesen = { throw IOException("kaputt") } }
        val zweite = SchichtSpeicher(eigeneKonfig.copy(lichtStufe = 0))
        assertTrue(migration(bestandsgeraet(), zweite, hue = HueConfigRepository(kaputt)).migriereEinmalig())
        assertEquals(1, zweite.gespeichert.size)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, zweite.konfig.modellStufe)
        assertEquals(0, zweite.konfig.lichtStufe)
        assertLichtFehler(TypModellMigration.Grund.HUE_REGELN_NICHT_LESBAR)

        // Alarm-Bestand gesperrt: auch das Licht schreibt nicht.
        TypModellMigration.standZuruecksetzen()
        val dritte = SchichtSpeicher(vorDemLicht)
        val gesperrt = mock<IAlarmRepository> { onBlocking { isPersistenceBlocked() } doReturn true }
        assertFalse(migration(bestandsgeraet(), dritte, alarme = gesperrt, hue = HueConfigRepository(hueStore(regel))).migriereEinmalig())
        assertTrue(dritte.gespeichert.isEmpty())
        assertLichtFehler(TypModellMigration.Grund.BESTAND_NICHT_LESBAR)
    }

    /**
     * Tor 5: nie ueber die Standard-Konfiguration (sie kommt auch, wenn `shift_prefs` vor der
     * Entsperrung gelesen wurde - die Uebersetzung schriebe die ganze Konfiguration darueber). Eine
     * Karte, weil Hue-Regeln liegen bleiben; ohne Regeln keine (Etappe-3-Test oben, Fall "frisch").
     */
    @Test
    fun `Licht - Standard-Konfiguration mit Hue-Regeln schreibt nichts und zeigt die Karte`() = runTest {
        val ohneAltbestand = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        val schicht = SchichtSpeicher(ShiftConfig.getDefaultConfig())
        val name = ShiftConfig.getDefaultConfig().definitions.first().name

        assertFalse(migration(ohneAltbestand, schicht, hue = HueConfigRepository(hueStore(hueRegel("r1", name)))).migriereEinmalig())

        assertTrue(schicht.gespeichert.isEmpty())
        assertLichtFehler(TypModellMigration.Grund.KEINE_EIGENEN_SCHICHTTYPEN)
    }

    /**
     * Zwilling von #234 (Kommentar des Eigentuemers in #248): frische Installation, beide Stufen 0,
     * Standard-Typen. Auf der Typ-Seite wird eine Lichtregel gespeichert (`config.copy`, die Stufen
     * bleiben) - die Konfiguration weicht vom Standard ab, Tor 5 greift nicht. Der Hue-Altbestand ist
     * leer: die Migration darf die Regel nicht durch "keine Regeln" ersetzen, nur die Stufe setzen.
     * Dazu der Geschwisterfall, in dem der Schutz je Typ greifen MUSS: liegt im Altbestand eine Regel
     * mit dem Namen dieses Typs und eine allgemeine, bekommt er nichts dazu, die anderen schon.
     */
    @Test
    fun `Zwilling 234 - am Typ gespeicherte Lichtregel bleibt, auch bei Hue-Regeln im Altbestand`() = runTest {
        val standard = ShiftConfig.getDefaultConfig()
        val eigene = hueRegel("am_typ", "")
        val gespeichert = standard.copy(
            definitions = standard.definitions.mapIndexed { i, typ -> if (i == 0) typ.copy(licht = typ.licht.mitRegeln(listOf(eigene))) else typ }
        )
        assertEquals(0, gespeichert.modellStufe)
        assertEquals(0, gespeichert.lichtStufe)
        val leererBestand = { SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND }) }

        // Fall des Eigentuemers: Hue-Altbestand leer.
        val schicht = SchichtSpeicher(gespeichert)
        assertTrue(migration(leererBestand(), schicht, hue = HueConfigRepository(SpeicherStore())).migriereEinmalig())
        assertEquals("die gespeicherte Lichtregel bleibt", listOf(eigene), schicht.konfig.definitions.first().licht.regeln)
        assertEquals(
            "ausser den Stufen aendert sich nichts",
            gespeichert.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE, lichtStufe = TypLichtUebersetzung.LICHT_STUFE),
            schicht.konfig
        )

        // Geschwister: Regel mit dem Namen des ersten Typs und eine allgemeine im Altbestand.
        val mitNamen = hueRegel("alt_name", standard.definitions.first().name)
        val alle = hueRegel("alt_alle", HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN)
        val zweite = SchichtSpeicher(gespeichert)
        assertTrue(migration(leererBestand(), zweite, hue = HueConfigRepository(hueStore(mitNamen, alle))).migriereEinmalig())
        assertEquals("der erste Typ behaelt nur seine Regel", listOf(eigene), zweite.konfig.definitions.first().licht.regeln)
        val andere = zweite.konfig.definitions.drop(1).filter { it.kategorie != ShiftKategorie.KRANK && it.kategorie != ShiftKategorie.IGNORIEREN }
        assertTrue("die Standard-Konfiguration hat weitere Typen mit Wecker", andere.isNotEmpty())
        andere.forEach { typ -> assertEquals(listOf(alle.copy(id = TypLichtUebersetzung.kopieId(alle.id, typ.id))), typ.licht.regeln) }
    }

    // --- Licht: Vorab-Sonnenaufgang neu planen (#249, Betreuer-Entscheidung) --------------------

    /**
     * `scheduleSunriseStarts()` raeumt zuerst ab und liest dann die Regeln am Typ - vor dem
     * Licht-Schritt trugen die Typen keine. Ohne Neuplanen fehlte die Rampe bis zum naechsten
     * Anlass (bis zu 24 h). Geplant wird nur nach einem GESCHRIEBENEN Licht-Schritt, nie nach
     * Dimmen und DND allein, nie bei "schon erledigt", "verschoben" oder "gescheitert".
     */
    @Test
    fun `Licht - nur ein geschriebener Licht-Schritt plant den Vorab-Sonnenaufgang neu, genau einmal`() = runTest {
        val regel = hueRegel("r1", "Frueh")
        val schicht = SchichtSpeicher(vorDemLicht)

        assertTrue(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hueStore(regel))).migriereEinmalig())
        verify(scheduler, times(1)).recalculateSchedule()
        // Zweiter Lauf: schon erledigt - kein weiteres Neuplanen.
        assertFalse(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hueStore(regel))).migriereEinmalig())
        verify(scheduler, times(1)).recalculateSchedule()
    }

    @Test
    fun `Licht - Dimmen allein, verschoben oder gescheitert plant nie neu`() = runTest {
        val regel = hueRegel("r1", "Frueh")

        // Nur der Schritt Dimmen und DND schreibt (Licht schon erledigt).
        val nurDimmen = SchichtSpeicher(eigeneKonfig)
        assertTrue(migration(bestandsgeraet(), nurDimmen).migriereEinmalig())
        assertEquals(1, nurDimmen.gespeichert.size)
        // Gesperrt: beide Schritte verschoben.
        val gesperrt = SchichtSpeicher(vorDemLicht)
        assertFalse(migration(bestandsgeraet(), gesperrt, entsperrt = false, hue = HueConfigRepository(hueStore(regel))).migriereEinmalig())
        // Standard-Konfiguration ohne Hue-Regeln: Licht verschoben.
        val ohneAltbestand = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        assertFalse(migration(ohneAltbestand, SchichtSpeicher(ShiftConfig.getDefaultConfig())).migriereEinmalig())
        // Hue-Store unlesbar: Licht gescheitert.
        val kaputt = hueStore(regel).apply { beimLesen = { throw IOException("kaputt") } }
        assertFalse(migration(bestandsgeraet(), SchichtSpeicher(vorDemLicht), hue = HueConfigRepository(kaputt)).migriereEinmalig())
        assertLichtFehler(TypModellMigration.Grund.HUE_REGELN_NICHT_LESBAR)

        verify(scheduler, never()).recalculateSchedule()
    }

    /**
     * Ein Fehler beim Neuplanen kippt nie den Ausgang: `schritt()` machte aus jedem Wurf
     * "Gescheitert" - die Karte im System-Status meldete dann eine Uebernahme als gescheitert, die
     * geschrieben ist. Beide Wege: `recalculateSchedule()` wirft, und schon `Lazy.get()` wirft.
     */
    @Test
    fun `Licht - ein Fehler beim Neuplanen laesst die Stufe geschrieben und den Stand auf Erledigt`() = runTest {
        doThrow(IllegalStateException("Planung kaputt")).whenever(scheduler).recalculateSchedule()
        val planerKaputt = dagger.Lazy<HueSmartScheduler> { throw IllegalStateException("Graph kaputt") }
        for (planer in listOf(dagger.Lazy { scheduler }, planerKaputt)) {
            TypModellMigration.standZuruecksetzen()
            val schicht = SchichtSpeicher(vorDemLicht)

            assertTrue(migration(bestandsgeraet(), schicht, hue = HueConfigRepository(hueStore(hueRegel("r1", "Frueh"))), planer = planer).migriereEinmalig())

            assertEquals(TypLichtUebersetzung.LICHT_STUFE, schicht.konfig.lichtStufe)
            assertEquals(1, schicht.gespeichert.size)
            assertEquals(TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)
        }
        verify(scheduler, times(1)).recalculateSchedule()
    }

    // --- Licht: Import und beide Vorlagen verlustfrei -------------------------------------------

    @Test
    fun `Import der nachgebauten Vorlage - die Hue-Regeln stehen verlustfrei an den Typen`() = runTest {
        importiereUndPruefeLichtVerlustfrei(UmbauVorlagen.nachgebaut())
    }

    @Test
    fun `Import der echten Vorlage - die Hue-Regeln stehen verlustfrei an den Typen, falls lokal vorhanden`() = runTest {
        importiereUndPruefeLichtVerlustfrei(UmbauVorlagen.echteOderUeberspringen())
    }

    /**
     * Ein alter Export (ohne `lichtStufe`) auf einem schon migrierten Geraet, durch den ECHTEN Import:
     * danach steht jede Hue-Regel der Datei wortgleich am Typ ihres Namens (allgemeine als Kopie an
     * jedem Typ mit moeglichem Wecker, an einem stummen Typ ausgeschaltet), der Hue-Store traegt den
     * Altbestand unveraendert, und ausser Licht und Stufe aendert der Schritt nichts. Die Erwartung ist
     * hier unabhaengig von [TypLichtUebersetzung] aufgeschrieben. Keine Werte aus der Datei in den
     * Meldungen - die echte Vorlage gehoert dem Eigentuemer.
     */
    private suspend fun importiereUndPruefeLichtVerlustfrei(datei: String) {
        val store = SpeicherStore(mutablePreferencesOf().apply { this[KEY_DIMMER_MARKER] = DimmerModellMigration.STAND })
        val hue = SpeicherStore()
        val schicht = SchichtSpeicher(eigeneKonfig.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE))
        val import = ConfigBackupUseCase(
            mainDataStore = store,
            hueDataStore = hue,
            shiftUseCase = schicht,
            armierer = armierer,
            dimmerModellMigration = DimmerModellMigration(kontext(), store, DimRuleUseCase(DimRuleRepository(store)), mock()),
            rufbereitschaftMigration = RufbereitschaftMigration(kontext(), DndPrefs(store), schicht),
            typModellMigration = migration(store, schicht, hue = HueConfigRepository(hue)),
            hueLightUseCase = mock(),
            hueRuleUseCase = mock(),
            hueSmartScheduler = { mock<HueSmartScheduler>() }
        )

        assertTrue(import.import(datei).isSuccess)

        val altRegeln = HueConfigRepository(hue).getScheduleRules().getOrThrow()
        assertTrue("die Vorlage traegt Hue-Regeln", altRegeln.isNotEmpty())
        val rohAusDatei = hueJson.decodeFromString(ConfigBackup.serializer(), datei).hue["hue_schedule_rules"]?.value
        assertEquals("kopieren statt verschieben: der Hue-Store traegt den Altbestand wortgleich", rohAusDatei, hue.stand.value[KEY_HUE_RULES])

        assertEquals("Import, Etappe 3, Licht - je ein Schreibvorgang", 3, schicht.gespeichert.size)
        val vorLicht = schicht.gespeichert[1]
        val nachher = schicht.konfig
        assertEquals(TypLichtUebersetzung.LICHT_STUFE, nachher.lichtStufe)
        assertTrue("die Vorlage hat noch kein Licht am Typ", vorLicht.definitions.all { it.licht == TypLicht() })
        assertEquals(
            "ausser Licht und Stufe aendert der Licht-Schritt nichts",
            vorLicht,
            nachher.copy(definitions = nachher.definitions.map { it.copy(licht = TypLicht()) }, lichtStufe = vorLicht.lichtStufe)
        )

        val (allgemeine, mitNamen) = altRegeln.partition { it.shiftPattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true) }
        val zielJeRegel = mitNamen.map { r -> nachher.definitions.indexOfFirst { it.name.equals(r.shiftPattern, ignoreCase = true) } }
        assertTrue("jede Regel mit Namen trifft einen Typ (sonst ginge sie verloren)", zielJeRegel.none { it < 0 })
        nachher.definitions.forEachIndexed { i, typ ->
            val eigene = mitNamen.filterIndexed { n, _ -> zielJeRegel[n] == i }
            val kopien = if (typ.kategorie == ShiftKategorie.KRANK || typ.kategorie == ShiftKategorie.IGNORIEREN) {
                emptyList()
            } else {
                allgemeine.map { it.copy(id = TypLichtUebersetzung.kopieId(it.id, typ.id)) }
            }
            val erwartet = (eigene + kopien).map { if (typ.isSilent) it.copy(enabled = false) else it }
            assertEquals("Typ #$i: Lichtregeln", erwartet, typ.licht.regeln)
            assertEquals("Typ #$i: nichts Undeutbares", 0, typ.licht.unlesbareRegeln)
        }

        // Verlustfrei auch gespeichert: Schreiben und Lesen wie `ShiftConfigRepository` ergibt dasselbe.
        val repoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(nachher, repoJson.decodeFromString(ShiftConfig.serializer(), repoJson.encodeToString(ShiftConfig.serializer(), nachher)))
        assertEquals(TypModellMigration.Stand.Erledigt, TypModellMigration.stand.value)
    }

    private fun assertLichtFehler(grund: TypModellMigration.Grund) = assertEquals(
        TypModellMigration.Stand.Fehlgeschlagen(grund, TypModellMigration.Schritt.LICHT),
        TypModellMigration.stand.value
    )

    private fun assertFehler(grund: TypModellMigration.Grund) =
        assertEquals(TypModellMigration.Stand.Fehlgeschlagen(grund), TypModellMigration.stand.value)

    private companion object {
        const val VORLAGE = "umbau/vorlage-nachgebaut-2026-10-16.json"
    }
}
