package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TypModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.AltbestandFake
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.EchteSchichtKonfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.doThrow
import java.time.LocalTime
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Review-Befund #140: Der Konfigurations-Import schreibt zwei Eingaben der Hue-
 * Sonnenaufgangsplanung - den Ort (`tageslicht_land`/`tageslicht_plz`) und die Hue-Regeln samt
 * `hellAb` -, zog die Planung aber nicht nach. Der Scheduler reagiert von sich aus nur auf
 * Alarm-Aenderungen und den Tagesplaner: eine Regel, die am alten Ort als "entfaellt" galt,
 * bekam bis zu 24 h keinen Vorlauf-Worker. Gleicher Nachzug wie im TageslichtViewModel.
 */
class ImportHueNachzugTest {

    /** Minimaler DataStore im Speicher - `edit {}` laeuft ueber [updateData]. */
    private class SpeicherStore : DataStore<Preferences> {
        private val stand = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = stand
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(stand.value).also { stand.value = it }
    }

    private fun useCase(scheduler: HueSmartScheduler) = ConfigBackupUseCase(
        mainDataStore = SpeicherStore(),
        hueDataStore = SpeicherStore(),
        shiftUseCase = mock<IShiftUseCase>(),
        armierer = mock<ZeitkettenArmierer>(),
        dimmerModellMigration = mock<DimmerModellMigration>(),
        rufbereitschaftMigration = mock<RufbereitschaftMigration>(),
        // #216: neuer Anlass der Typ-Modellmigration - hier nicht Gegenstand, nur Konstruktor.
        typModellMigration = mock<TypModellMigration> { onBlocking { migriereEinmalig() } doReturn false },
        hueLightUseCase = mock<IHueLightUseCase>(),
        hueRuleUseCase = mock<IHueRuleUseCase>(),
        hueSmartScheduler = { scheduler }
    )

    /** Datei, die nur den Ort traegt - genau der Fall aus dem Befund. */
    private val ortDatei = """
        {
          "formatVersion": 1,
          "settings": {
            "tageslicht_land": { "type": "string", "value": "DE" },
            "tageslicht_plz": { "type": "string", "value": "52062" }
          }
        }
    """.trimIndent()

    @Test
    fun `Import zieht die Hue-Sonnenaufgangsplanung nach`() = runTest {
        val scheduler = mock<HueSmartScheduler>()

        val ergebnis = useCase(scheduler).import(ortDatei)

        assertTrue("Import selbst muss gelingen: ${ergebnis.exceptionOrNull()}", ergebnis.isSuccess)
        assertEquals(2, ergebnis.getOrThrow().settingsKeys)
        verify(scheduler, times(1)).recalculateSchedule()
    }

    @Test
    fun `Fehlschlag des Hue-Nachzugs meldet den geschriebenen Import nicht als gescheitert`() = runTest {
        val scheduler = mock<HueSmartScheduler> {
            on { recalculateSchedule() } doThrow IllegalStateException("Scheduler kaputt")
        }

        val ergebnis = useCase(scheduler).import(ortDatei)

        assertTrue("Best-effort: ${ergebnis.exceptionOrNull()}", ergebnis.isSuccess)
        verify(scheduler, times(1)).recalculateSchedule()
    }

    @get:Rule
    val ordner = TemporaryFolder()

    /**
     * Umbau Etappe 4 (#250): Werte schreiben -> Migration -> Ziel-Abgleich. Ein ALTER Export traegt
     * die Lichtregeln noch im Hue-Store, mit den Lampen-ids des Quellgeraets. Danach muessen die
     * Regeln AM TYP auf die Lampe dieser Bridge zeigen. Liefe der Abgleich vor der Migration, glich
     * er noch leere Typen ab und die Migration kopierte danach die alte id; glich er den Hue-Store
     * ab, stuende die alte id ebenso am Typ.
     */
    @Test
    fun `Import-Reihenfolge - nach dem Import stehen die abgeglichenen Ziele am Typ`() = runBlocking {
        val k = EchteSchichtKonfiguration(ordner.newFolder())
        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val hueStore = SpeicherStore()
            val schichten = mock<IShiftUseCase> {
                onBlocking { getCurrentShiftConfig() } doSuspendableAnswer { k.repo.getCurrentShiftConfig() }
                onBlocking { saveShiftConfig(any()) } doSuspendableAnswer { k.repo.saveShiftConfig(it.getArgument(0)) }
            }
            // Stand-in fuer den Licht-Schritt der Typ-Modellmigration (#248, eigener Test
            // TypModellMigrationTest): die importierten Hue-Regeln ueber die ECHTE Uebersetzung an die Typen.
            val migration = mock<TypModellMigration> {
                onBlocking { migriereEinmalig() } doSuspendableAnswer {
                    val roh = hueStore.data.first()[stringPreferencesKey("hue_schedule_rules")] ?: "[]"
                    val alt = json.decodeFromString(ListSerializer(HueScheduleRule.serializer()), roh)
                    val config = k.repo.getCurrentShiftConfig().getOrThrow()
                    k.repo.saveShiftConfig(TypLichtUebersetzung.uebersetze(config, alt).config).getOrThrow()
                    true
                }
            }
            // Die neue Bridge: das Bad heisst hier Lampe 3 (auf dem Quellgeraet Lampe 7).
            val neueBridge = LightTargets(lights = listOf(HueLight(id = "3", name = "Bad", state = LightState(on = false))))
            val licht = mock<IHueLightUseCaseAdvanced> {
                onBlocking { getAllLightTargets() } doSuspendableAnswer { Result.success(neueBridge) }
            }
            val altbestand = AltbestandFake()
            val regeln = HueRuleUseCase(
                configRepository = altbestand,
                lightUseCase = licht,
                typLicht = TypLichtQuelle.Leser { k.repo.getCurrentShiftConfig() },
                schichtKonfiguration = k.repo
            )
            val useCase = ConfigBackupUseCase(
                mainDataStore = SpeicherStore(),
                hueDataStore = hueStore,
                shiftUseCase = schichten,
                armierer = mock<ZeitkettenArmierer>(),
                dimmerModellMigration = mock<DimmerModellMigration>(),
                rufbereitschaftMigration = mock<RufbereitschaftMigration>(),
                typModellMigration = migration,
                hueLightUseCase = licht,
                hueRuleUseCase = regeln,
                hueSmartScheduler = { mock<HueSmartScheduler>() }
            )
            val altRegel = HueScheduleRule(
                id = "r1",
                name = "Bad an",
                shiftPattern = "F",
                timeRanges = listOf(
                    HueTimeRange(
                        actions = listOf(
                            HueLightAction(
                                targetType = TargetType.LIGHT,
                                targetId = "7",
                                targetName = "Bad",
                                actionType = ActionType.TURN_ON,
                                on = true,
                                brightness = 200
                            )
                        )
                    )
                )
            )
            val typen = ShiftConfig(
                definitions = listOf(ShiftDefinition(id = "f", name = "F", keywords = listOf("F"), alarmTime = LocalTime.of(5, 0)))
            )
            val regelText = json.encodeToString(ListSerializer(HueScheduleRule.serializer()), listOf(altRegel))
            val datei = "{\"formatVersion\":1,\"shiftConfig\":${json.encodeToString(ShiftConfig.serializer(), typen)}," +
                "\"hue\":{\"hue_schedule_rules\":{\"type\":\"string\",\"value\":${JsonPrimitive(regelText)}}}}"

            val ergebnis = useCase.import(datei).getOrThrow()

            assertEquals("geprueft und alles zugeordnet", 0, ergebnis.unresolvedHueTargets)
            val amTyp = k.repo.getCurrentShiftConfig().getOrThrow().definitions.single().licht.regeln
            assertEquals(listOf("3"), amTyp.flatMap { r -> r.lightActions.map { it.targetId } })
            assertTrue("Altbestand nicht abgeglichen", altbestand.store.isEmpty())
        } finally {
            k.schliessen()
        }
    }
}
