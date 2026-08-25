package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

/**
 * Haelt fest, dass die Regel-VORSCHAU ("Regel testen") immer hinter sich aufraeumt.
 *
 * Der Fehler, den das verhindert: Das Aufraeumen hing am Auto-Aus DER REGEL - und das steht bei
 * einer neu angelegten Regel auf "aus". Der Vorschau-Knopf schaltete das Licht also an und liess
 * es an, ohne Weg zurueck ausser der Hue-App. Am Fairphone 6 aufgefallen (15.07.2026).
 *
 * Der Unterschied zum echten Weckvorgang ist Absicht und darf nicht eingeebnet werden: Der
 * ECHTE Alarm laesst das Licht an, wenn die Regel kein Auto-Aus hat (dann will der Nutzer das
 * so). Nur die Vorschau raeumt immer auf - sie ist ein Ausprobieren, kein Lichtschalter.
 */
class RulePreviewCleanupTest {

    private class FakeLightUseCase : IHueLightUseCaseAdvanced {
        val autoRevertCalls = mutableListOf<Pair<List<LightAction>, Duration>>()
        var batchWithoutRevertCalls = 0
        val sunriseCalls = mutableListOf<String>()

        override suspend fun executeActionsWithAutoRevert(
            actions: List<LightAction>,
            revertAfter: Duration
        ): Result<BatchActionResult> {
            autoRevertCalls += actions to revertAfter
            return Result.success(batchResult(actions.size))
        }

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> {
            batchWithoutRevertCalls++
            return Result.success(batchResult(actions.size))
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

        private fun batchResult(count: Int) = BatchActionResult(
            totalActions = count,
            successfulActions = count,
            failedActions = emptyList<LightActionResult>(),
            overallSuccess = true
        )

        // Von executeRuleNow nicht benutzt:
        override suspend fun getAllLightTargets(): Result<LightTargets> = Result.success(LightTargets())
        override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> =
            Result.success(LightActionResult(true, action.targetId))
        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> =
            Result.success(0)
        override suspend fun flashLight(lightId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeConfigRepository : IHueConfigRepository {
        override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
        override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
        override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(emptyList())
        override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRules(transform: (List<HueSchedule>) -> List<HueSchedule>): Result<Unit> = Result.success(Unit)
        override suspend fun clearConfiguration(): Result<Unit> = Result.success(Unit)
        override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
    }

    private fun ruleWith(
        on: Boolean?,
        durationMinutes: Int? = null,
        sunrise: SunriseConfig? = null
    ): HueSchedule = HueSchedule(
        id = "rule_test",
        name = "Testregel",
        shiftPattern = "Frühschicht",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "1",
                        actionType = if (on == true) ActionType.TURN_ON else ActionType.TURN_OFF,
                        on = on,
                        brightness = if (on == true) 200 else null,
                        duration = durationMinutes,
                        isGroup = true
                    )
                )
            )
        ),
        sunrise = sunrise
    )

    private fun newUseCase(light: FakeLightUseCase) = HueRuleUseCase(FakeConfigRepository(), light)

    /** Der eigentliche Fehlerfall: neue Regel, Auto-Aus aus - das Licht blieb an. */
    @Test
    fun `Vorschau schaltet auch ohne konfiguriertes Auto-Aus wieder aus`() = runTest {
        val light = FakeLightUseCase()

        val result = newUseCase(light).executeRuleNow(ruleWith(on = true, durationMinutes = null))

        assertTrue("Vorschau soll erfolgreich sein", result.isSuccess)
        assertEquals(
            "Die Vorschau muss genau einmal mit Auto-Revert ausfuehren",
            1,
            light.autoRevertCalls.size
        )
        assertEquals(
            "Ohne Revert ausfuehren waere der alte Fehler - das Licht bliebe an",
            0,
            light.batchWithoutRevertCalls
        )
        // Der Nutzer muss erfahren, dass das Aus zur Vorschau gehoert und nicht zur Regel.
        assertNotNull(
            "Ohne Auto-Aus braucht es einen Hinweis, dass nur der Test ausschaltet",
            result.getOrThrow().autoOffTestNote
        )
    }

    /** Mit Auto-Aus: gleiches Verhalten, nur verkuerzt - und ein anderer Hinweistext. */
    @Test
    fun `Vorschau verkuerzt ein konfiguriertes Auto-Aus`() = runTest {
        val light = FakeLightUseCase()

        val result = newUseCase(light).executeRuleNow(ruleWith(on = true, durationMinutes = 30))

        assertEquals(1, light.autoRevertCalls.size)
        val (_, revertAfter) = light.autoRevertCalls.single()
        assertTrue(
            "Der Test darf nicht die echten 30 Minuten warten, sondern nur Sekunden",
            revertAfter.inWholeSeconds in 1..120
        )
        assertTrue(
            "Hinweis muss die konfigurierte Zeit erwaehnen",
            result.getOrThrow().autoOffTestNote!!.contains("konfigurierte")
        )
    }

    /**
     * Eine reine Ausschalt-Regel laesst nichts an, was zurueckzunehmen waere. Der Hinweis
     * "Lichter gehen wieder aus" waere hier schlicht falsch.
     */
    @Test
    fun `Ausschalt-Regel bekommt keinen Aufraeum-Hinweis`() = runTest {
        val light = FakeLightUseCase()

        val result = newUseCase(light).executeRuleNow(ruleWith(on = false))

        assertNull(
            "Nichts eingeschaltet -> nichts zu versprechen",
            result.getOrThrow().autoOffTestNote
        )
    }

    /**
     * Das Aus muss NACH der verkuerzten Rampe kommen. Feuert es waehrend der Rampe, wuergt es
     * die laufende Bridge-Transition mitten im Aufblenden ab - die Vorschau saehe kaputt aus.
     */
    @Test
    fun `Sonnenaufgangs-Vorschau schaltet erst nach der Rampe aus`() = runTest {
        val light = FakeLightUseCase()

        newUseCase(light).executeRuleNow(
            ruleWith(on = true, sunrise = SunriseConfig(enabled = true, durationMinutes = 20))
        )

        assertEquals("Die Rampe muss laufen", 1, light.sunriseCalls.size)
        val (_, revertAfter) = light.autoRevertCalls.single()
        assertTrue(
            "Aus (${revertAfter.inWholeSeconds}s) darf nicht in die verkuerzte Rampe fallen",
            revertAfter.inWholeSeconds > 60
        )
    }

    /**
     * Der Szenenfall derselben Zusicherung. Eine Szenen-Aktion traegt `on == null` (gesendet
     * wird nur `{"scene": ...}`), schaltet aber sehr wohl Licht an - der Filter in
     * executeActionsWithAutoRevert haette sie ohne die Szenen-Bedingung durchfallen lassen, und
     * die Vorschau haette den Raum dauerhaft erleuchtet zurueckgelassen. Genau der urspruengliche
     * Bug, nur in neuer Gestalt.
     */
    @Test
    fun `Szenen-Vorschau raeumt ebenfalls auf und sagt es auch`() = runTest {
        val light = FakeLightUseCase()

        val result = newUseCase(light).executeRuleNow(szenenRegel())

        assertEquals("Auch die Szene muss mit Auto-Revert laufen", 1, light.autoRevertCalls.size)
        assertEquals(0, light.batchWithoutRevertCalls)

        val (actions, _) = light.autoRevertCalls.single()
        val aktion = actions.single()
        assertEquals("Es faehrt genau die Szene mit", "wz-nacht", aktion.sceneId)
        assertNull("Kein on neben der Szene", aktion.on)
        assertNull("Keine Helligkeit neben der Szene", aktion.brightness)
        assertTrue("Die Szene geht an ihre Gruppe", aktion.isGroup)

        val hinweis = result.getOrThrow().autoOffTestNote
        assertNotNull("Auch hier muss der Nutzer erfahren, dass nur der Test ausschaltet", hinweis)
        assertTrue("Der Hinweis muss den raumweiten Effekt benennen", hinweis!!.contains("Raum"))
    }

    private fun szenenRegel(): HueSchedule = HueSchedule(
        id = "rule_szene",
        name = "Szenenregel",
        shiftPattern = "Frühschicht",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "1",
                        targetName = "Wohnzimmer",
                        actionType = ActionType.TURN_ON,
                        // Gespeicherte Zusage fuer autoOffTargetsOf(), NICHT gesendeter Wert.
                        on = true,
                        isGroup = true,
                        sceneId = "wz-nacht",
                        sceneName = "Nachtlicht"
                    )
                )
            )
        )
    )
}
