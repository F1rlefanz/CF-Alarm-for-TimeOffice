package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeTimer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.modus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration

/**
 * Szenen als Regel-Ziel: Validierung, Ausfuehrung, Auto-Aus und Migrationsfreiheit.
 *
 * Die tragende Entscheidung, die hier festgenagelt wird: Eine Szene ist KEIN vierter Zieltyp,
 * sondern ein Zusatz zu einem GRUPPEN-Ziel. Sie traegt `isGroup = true`, `targetId` = Gruppe und
 * `on = true` - Letzteres als gespeicherte ZUSAGE fuer `autoOffTargetsOf()`, nicht als gesendeter
 * Wert. Genau dadurch bleiben Ausfuehrungspfad, Auto-Aus-Rechnung und BridgeTimer unveraendert.
 */
class HueSceneRuleTest {

    // --- Fakes -------------------------------------------------------------------------------

    private class FakeConfigRepository(private val rules: List<HueSchedule>) : IHueConfigRepository {
        override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
        override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
        override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(rules)
        override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRules(transform: (List<HueSchedule>) -> List<HueSchedule>): Result<Unit> = Result.success(Unit)
        override suspend fun clearConfiguration(): Result<Unit> = Result.success(Unit)
        override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
    }

    private class FakeLightUseCase : IHueLightUseCaseAdvanced {
        val autoOffCalls = mutableListOf<Pair<List<AutoOffTarget>, String>>()
        val batchCalls = mutableListOf<List<LightAction>>()
        val sunriseCalls = mutableListOf<String>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoOffCalls += targets to shiftName
            return Result.success(targets.size)
        }

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> {
            batchCalls += actions
            return Result.success(
                BatchActionResult(actions.size, actions.size, emptyList(), true)
            )
        }

        override suspend fun executeActionsWithAutoRevert(
            actions: List<LightAction>,
            revertAfter: Duration
        ): Result<BatchActionResult> {
            batchCalls += actions
            return Result.success(
                BatchActionResult(actions.size, actions.size, emptyList(), true)
            )
        }

        override suspend fun startSunrise(
            targetId: String,
            isGroup: Boolean,
            startKelvin: Int,
            endKelvin: Int,
            endBrightness: Int,
            durationMinutes: Int
        ): Result<Unit> {
            sunriseCalls += targetId
            return Result.success(Unit)
        }

        override suspend fun getAllLightTargets(): Result<LightTargets> = Result.success(LightTargets())
        override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> =
            Result.success(LightActionResult(true, action.targetId))
        override suspend fun flashLight(lightId: String): Result<Unit> = Result.success(Unit)
    }

    // --- Ausfuehrung ------------------------------------------------------------------------

    @Test
    fun `eine Szenenregel schickt NUR die Szene an die Bridge`() = runTest {
        val light = FakeLightUseCase()

        useCase(listOf(szenenRegel()), light).executeRulesForAlarm(shift("Frühdienst"), LocalTime.of(5, 30))

        val aktion = light.batchCalls.single().single()
        assertEquals("wz-nacht", aktion.sceneId)
        assertEquals("Die Szene geht an ihre Gruppe", "1", aktion.targetId)
        assertTrue(aktion.isGroup)
        // Nichts darf neben der Szene im selben PUT mitfahren - die Szene bestimmt das selbst.
        assertNull(aktion.on)
        assertNull(aktion.brightness)
        assertNull(aktion.hue)
        assertNull(aktion.saturation)
        assertNull(aktion.colorTemperature)
        assertNull(aktion.transitionTime)
    }

    @Test
    fun `das Auto-Aus einer Szenenregel trifft die Gruppe - ohne zweiten Rechenweg`() = runTest {
        val light = FakeLightUseCase()

        useCase(listOf(szenenRegel(autoOffMinuten = 30)), light)
            .executeRulesForAlarm(shift("Frühdienst"), LocalTime.of(5, 30))

        val (targets, _) = light.autoOffCalls.single()
        val ziel = targets.single()
        assertEquals("1", ziel.targetId)
        assertTrue("Das Aus geht an /groups/<id>/action", ziel.isGroup)
        assertEquals(30, ziel.delayMinutes)

        // Und genau daraus entsteht der Bridge-Zeitplan - unveraenderter BridgeTimer.
        assertEquals("/groups/1/action", BridgeTimer.resourcePath(ziel.targetId, ziel.isGroup))
        assertEquals("PT00:30:00", BridgeTimer.timerPattern(ziel.delayMinutes))
    }

    @Test
    fun `ohne konfiguriertes Auto-Aus entsteht kein Zeitplan`() = runTest {
        val light = FakeLightUseCase()

        useCase(listOf(szenenRegel()), light).executeRulesForAlarm(shift("Frühdienst"), LocalTime.of(5, 30))

        assertTrue(light.autoOffCalls.single().first.isEmpty())
    }

    // --- Validierung -------------------------------------------------------------------------

    @Test
    fun `Szene mit Helligkeit ist ungueltig`() = runTest {
        val regel = szenenRegel().mitAktion { it.copy(brightness = 200) }

        val ergebnis = useCase(emptyList()).validateRule(regel)

        // Die Zwei-Ebenen-Falle: die PRUEFUNG ist gelungen, die REGEL ist es nicht.
        assertTrue("Die Pruefung selbst gelingt", ergebnis.isSuccess)
        assertFalse("Die Regel ist ungueltig", ergebnis.getOrThrow().isValid)
        assertTrue(ergebnis.getOrThrow().errors.any { it.contains("Helligkeit und Farbe selbst") })
    }

    @Test
    fun `Szene ohne Gruppe ist ungueltig`() = runTest {
        val regel = szenenRegel().mitAktion { it.copy(isGroup = false, targetType = TargetType.LIGHT) }

        val ergebnis = useCase(emptyList()).validateRule(regel)

        assertFalse(ergebnis.getOrThrow().isValid)
        assertTrue(ergebnis.getOrThrow().errors.any { it.contains("Gruppe") })
    }

    @Test
    fun `Sonnenaufgang und Szene zusammen sind ungueltig`() = runTest {
        val regel = szenenRegel().copy(sunrise = SunriseConfig(enabled = true))

        val ergebnis = useCase(emptyList()).validateRule(regel)

        assertFalse(ergebnis.getOrThrow().isValid)
        assertTrue(ergebnis.getOrThrow().errors.any { it.contains("schliessen sich aus") })
    }

    @Test
    fun `eine saubere Szenenregel ist gueltig`() = runTest {
        val ergebnis = useCase(emptyList()).validateRule(szenenRegel(autoOffMinuten = 30))

        assertTrue(ergebnis.getOrThrow().errors.joinToString(), ergebnis.getOrThrow().isValid)
    }

    // --- Modus-Herleitung --------------------------------------------------------------------

    @Test
    fun `der Modus wird aus der Regel abgeleitet - Sonnenaufgang schlaegt Szene`() {
        assertEquals(HueRuleModus.SZENE, szenenRegel().modus)
        assertEquals(HueRuleModus.MANUELL, manuelleRegel().modus)
        assertEquals(
            HueRuleModus.SONNENAUFGANG,
            manuelleRegel().copy(sunrise = SunriseConfig(enabled = true)).modus
        )
        // Bestandsdaten koennen beides tragen, obwohl validateRule das ablehnt. Die Anzeige darf
        // dann nicht undefiniert sein.
        assertEquals(
            HueRuleModus.SONNENAUFGANG,
            szenenRegel().copy(sunrise = SunriseConfig(enabled = true)).modus
        )
        // Ein abgeschalteter Sonnenaufgang ist kein Sonnenaufgang.
        assertEquals(
            HueRuleModus.MANUELL,
            manuelleRegel().copy(sunrise = SunriseConfig(enabled = false)).modus
        )
    }

    // --- Migrationsfreiheit ------------------------------------------------------------------

    @Test
    fun `Bestands-JSON ohne Szenenfelder dekodiert unveraendert`() {
        // Migrationsfreiheit als TEST, nicht als Behauptung: die Regeln liegen als eine einzige
        // JSON-Liste unter `hue_schedule_rules`, es gibt keine Versionierung und keinen
        // Migrationsschritt. Additiv-nullbare Felder sind der einzige Grund, warum das traegt.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val bestand = """
            [{"id":"rule_alt","name":"Alte Regel","shiftPattern":"Frühdienst","enabled":true,
              "timeRanges":[{"actions":[{"targetType":"GROUP","targetId":"1",
                "targetName":"Wohnzimmer","actionType":"TURN_ON","on":true,"brightness":200,
                "transitionTime":10,"isGroup":true}]}],"priority":0}]
        """.trimIndent()

        val regeln = json.decodeFromString<List<HueSchedule>>(bestand)

        val aktion = regeln.single().lightActions.single()
        assertNull(aktion.sceneId)
        assertNull(aktion.sceneName)
        assertFalse(aktion.isScene)
        assertEquals(HueRuleModus.MANUELL, regeln.single().modus)
        assertEquals(200, aktion.brightness)
    }

    @Test
    fun `eine Szenenregel ueberlebt den Serialisierungs-Rundlauf`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val regel = szenenRegel(autoOffMinuten = 30)

        val zurueck = json.decodeFromString<HueSchedule>(json.encodeToString(regel))

        assertEquals(regel, zurueck)
        assertTrue(zurueck.lightActions.single().isScene)
    }

    // --- Helfer ------------------------------------------------------------------------------

    private fun HueSchedule.mitAktion(transform: (HueLightAction) -> HueLightAction) = copy(
        timeRanges = timeRanges.map { it.copy(actions = it.actions.map(transform)) }
    )

    private fun szenenRegel(autoOffMinuten: Int? = null) = HueSchedule(
        id = "rule_szene",
        name = "Nachtlicht Frueh",
        shiftPattern = "Frühdienst",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "1",
                        targetName = "Wohnzimmer",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        duration = autoOffMinuten,
                        isGroup = true,
                        sceneId = "wz-nacht",
                        sceneName = "Nachtlicht"
                    )
                )
            )
        )
    )

    private fun manuelleRegel() = HueSchedule(
        id = "rule_manuell",
        name = "Manuell",
        shiftPattern = "Frühdienst",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "1",
                        targetName = "Wohnzimmer",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        isGroup = true
                    )
                )
            )
        )
    )

    private fun shift(name: String): ShiftMatch {
        val start = LocalDateTime.of(2026, 8, 26, 6, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(
                id = "def_$name",
                name = name,
                keywords = listOf(name),
                alarmTime = LocalTime.of(5, 30)
            ),
            calendarEvent = CalendarEvent(
                id = "event_$name",
                title = name,
                startTime = start,
                endTime = start.plusHours(8),
                calendarId = "cal"
            ),
            calculatedAlarmTime = start.minusMinutes(30)
        )
    }

    private fun useCase(rules: List<HueSchedule>, light: FakeLightUseCase = FakeLightUseCase()) =
        HueRuleUseCase(FakeConfigRepository(rules), light)
}
